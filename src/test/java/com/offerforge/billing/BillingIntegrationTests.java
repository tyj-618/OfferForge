package com.offerforge.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.auth.UserRepository;
import com.offerforge.email.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付费计费端到端（开关开，免费额度每日 1 次，高价目便于断言）：
 * 充值下单 → 模拟支付 → 余额到账 → 额度耗尽转计费模式开局 → token 扣费流水 →
 * 余额不足 402 引导 → 充值后恢复；付费模型无余额开局 402；免费模型不受影响。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "offerforge.billing.enabled=true",
        "offerforge.quota.enabled=true",
        "offerforge.quota.daily-limit=1",
        // 每阶段 2 题共 6 题：免费场次可达 5 题计次门槛，避免短场退还导致额度未真正耗尽
        "offerforge.interview.max-basics-questions=2",
        "offerforge.interview.max-project-questions=2",
        "offerforge.interview.max-deep-questions=2"
})
class BillingIntegrationTests {

    private static final String LONG_ANSWER = "我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。";
    /** Mock 客户端固定用量（100 输入/50 输出）× 测试价目（100000/200000 分每百万）× 1.2 加成 */
    private static final long CHARGE_PER_LLM_CALL = 24;
    /** 作答一回合含 2 次 LLM 调用（出题准备 + 回答生成），共扣 48 分 */
    private static final long CHARGE_PER_TURN = CHARGE_PER_LLM_CALL * 2;

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rechargeThenBillingModeChargesTokensAndRecoversAfterInsufficientBalance() throws Exception {
        username = "bill_user_" + System.nanoTime();
        String token = newUser(username);

        // 初始状态：开关开、余额 0；档位与模型价目可见
        JsonNode status0 = get("/api/billing/status", token);
        assertCode(status0, 0);
        assertThat(status0.at("/data/enabled").asBoolean()).isTrue();
        assertThat(status0.at("/data/provider").asText()).isEqualTo("mock");
        assertThat(status0.at("/data/balanceCents").asLong()).isZero();
        JsonNode packages = get("/api/billing/packages", token);
        assertThat(packages.at("/data/0/id").asText()).isEqualTo("pkg-10");
        assertThat(packages.at("/data/0/amountCents").asLong()).isEqualTo(1000);
        JsonNode models = get("/api/billing/models", token);
        assertThat(models.at("/data/1/id").asText()).isEqualTo("test-paid");
        assertThat(models.at("/data/1/paidOnly").asBoolean()).isTrue();

        // 下单 → 查询待支付 → 模拟支付到账；重复支付幂等不二次入账
        JsonNode created = post("/api/billing/orders", token, Map.of("packageId", "pkg-10"));
        assertCode(created, 0);
        String orderNo = created.at("/data/orderNo").asText();
        assertThat(orderNo).startsWith("OF");
        assertThat(created.at("/data/amountCents").asLong()).isEqualTo(1000);
        assertThat(created.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(created.at("/data/payHint").asText()).isNotBlank();
        assertThat(get("/api/billing/orders/" + orderNo, token).at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(post("/api/billing/mock-pay/" + orderNo, token, Map.of()).at("/data/paid").asBoolean()).isTrue();
        assertThat(post("/api/billing/mock-pay/" + orderNo, token, Map.of()).at("/data/paid").asBoolean()).isFalse();

        // 余额到账 + 充值流水可审计
        assertThat(get("/api/billing/status", token).at("/data/balanceCents").asLong()).isEqualTo(1000);
        JsonNode transactions = get("/api/billing/transactions", token);
        assertThat(transactions.at("/data/0/type").asText()).isEqualTo("RECHARGE");
        assertThat(transactions.at("/data/0/amountCents").asLong()).isEqualTo(1000);
        assertThat(transactions.at("/data/0/balanceAfterCents").asLong()).isEqualTo(1000);

        // 免费额度场次（第 1 场）正常消耗每日 1 次额度，免费模型不计费
        completeFullInterview(token);
        assertThat(get("/api/quota", token).at("/data/remaining").asInt()).isZero();
        assertThat(get("/api/billing/status", token).at("/data/balanceCents").asLong()).isEqualTo(1000);

        // 额度耗尽 + 有余额：第 2 场转计费模式，作答一回合按价目扣费
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        String turn1 = ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        assertThat(turn1).contains("event:done");
        assertThat(get("/api/billing/status", token).at("/data/balanceCents").asLong())
                .isEqualTo(1000 - CHARGE_PER_TURN);
        JsonNode consume = get("/api/billing/transactions", token).at("/data/0");
        assertThat(consume.at("/type").asText()).isEqualTo("CONSUME");
        assertThat(consume.at("/amountCents").asLong()).isEqualTo(CHARGE_PER_LLM_CALL);
        assertThat(consume.at("/balanceAfterCents").asLong()).isEqualTo(1000 - CHARGE_PER_TURN);
        assertThat(consume.at("/detail").asText()).isEqualTo("model:test-free");

        // 余额耗尽：回合预检中断并下发字符串业务码引导充值（测试辅助直接排空钱包）
        long userId = userRepository.findByUsername(username).orElseThrow().getId();
        walletService.consume(userId, 1000 - CHARGE_PER_TURN, null, null);
        assertThat(get("/api/billing/status", token).at("/data/balanceCents").asLong()).isZero();
        String rejected = ask(sessionId, token, LONG_ANSWER);
        assertThat(rejected).contains("INSUFFICIENT_BALANCE");

        // 充值后恢复：同一场次可继续作答
        JsonNode created2 = post("/api/billing/orders", token, Map.of("packageId", "pkg-10"));
        String orderNo2 = created2.at("/data/orderNo").asText();
        assertThat(post("/api/billing/mock-pay/" + orderNo2, token, Map.of()).at("/data/paid").asBoolean()).isTrue();
        assertThat(get("/api/billing/status", token).at("/data/balanceCents").asLong()).isEqualTo(1000);
        assertThat(ask(sessionId, token, LONG_ANSWER)).contains("event:done");
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    @Test
    void paidModelRequiresBalanceAndUnknownModelRejected() throws Exception {
        String token = newUser("bill_mdl_" + System.nanoTime());

        // 付费模型无余额：开局即 402 + 字符串业务码（不消耗任何资源）
        HttpResponse<String> rejected = postRaw("/api/interview/start", token, Map.of("model", "test-paid"));
        assertThat(rejected.statusCode()).isEqualTo(402);
        JsonNode body = objectMapper.readTree(rejected.body());
        assertThat(body.at("/code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(body.at("/balanceCents").asLong()).isZero();

        // 未知模型：参数错误拒绝（项目约定业务错误 HTTP 200 + body 业务码）
        HttpResponse<String> unknown = postRaw("/api/interview/start", token, Map.of("model", "no-such-model"));
        assertThat(unknown.statusCode()).isEqualTo(200);
        JsonNode unknownBody = objectMapper.readTree(unknown.body());
        assertThat(unknownBody.at("/code").asInt()).isEqualTo(40000);
        assertThat(unknownBody.at("/message").asText()).contains("不支持的模型");

        // 免费模型 + 有免费额度：正常开局不受付费链影响
        JsonNode start = post("/api/interview/start", token, Map.of("model", "test-free"));
        assertCode(start, 0);
        assertCode(post("/api/interview/" + start.at("/data/sessionId").asText() + "/finish", token, Map.of()), 0);
    }

    @Test
    void billingEndpointsRequireLogin() throws Exception {
        assertThat(get("/api/billing/status", null).at("/code").asInt()).isEqualTo(40100);
        assertThat(post("/api/billing/orders", null, Map.of("packageId", "pkg-10")).at("/code").asInt()).isEqualTo(40100);
    }

    /** 免费场次走完 6 题（≥5 计次门槛）：真实消耗每日额度，避免短场退还 */
    private void completeFullInterview(String token) throws Exception {
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        for (int round = 0; round < 12; round++) {
            String state = get("/api/interview/" + sessionId + "/status", token).at("/data/state").asText();
            if ("CLOSING".equals(state) || "FINISHED".equals(state)) {
                break;
            }
            ask(sessionId, token, LONG_ANSWER);
        }
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    /** 当前测试用户名（钱包排空辅助定位用户 id） */
    private String username;

    private String ask(String sessionId, String token, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String newUser(String name) throws Exception {
        String email = name.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", name, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", name, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private JsonNode post(String path, String token, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postRaw(path, token, body).body());
    }

    private HttpResponse<String> postRaw(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}

/**
 * 总开关关闭（test profile 默认）：status 正常返回供前端隐藏入口，下单/模拟支付一律拒绝。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingDisabledIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void endpointsRejectWhenBillingDisabled() throws Exception {
        String username = "bill_off_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        objectMapper.readTree(send("POST", "/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")).body());
        JsonNode login = objectMapper.readTree(send("POST", "/api/auth/login", null,
                Map.of("username", username, "password", "123456")).body());
        String token = login.at("/data/token").asText();

        // status 照常返回（前端据此隐藏充值入口）
        JsonNode status = objectMapper.readTree(send("GET", "/api/billing/status", token, null).body());
        assertThat(status.at("/code").asInt()).isZero();
        assertThat(status.at("/data/enabled").asBoolean()).isFalse();

        // 下单与模拟支付一律拒绝：服务暂未开放（503 业务码）
        JsonNode order = objectMapper.readTree(send("POST", "/api/billing/orders", token,
                Map.of("packageId", "pkg-10")).body());
        assertThat(order.at("/code").asInt()).isEqualTo(50301);
        assertThat(order.at("/message").asText()).contains("充值功能暂未开放");
        JsonNode mockPay = objectMapper.readTree(send("POST", "/api/billing/mock-pay/OF123", token,
                Map.of()).body());
        assertThat(mockPay.at("/code").asInt()).isEqualTo(50301);
    }

    private HttpResponse<String> send(String method, String path, String token, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body == null ? Map.of() : body),
                            StandardCharsets.UTF_8));
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
