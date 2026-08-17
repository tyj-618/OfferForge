<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'

// 文档正文含 Markdown（标题/列表/表格/引用），与面试气泡保持一致的渲染配置
marked.use({ gfm: true, breaks: false })

// 内置 Markdown 资源（任务 13）：eager 打包进前端，后续新增文档只需在 src/docs 下添加 .md 并登记目录
const modules = import.meta.glob('../docs/*.md', { query: '?raw', import: 'default', eager: true })

// 侧边目录：前两项为已交付文档，其余为预留目录位（标注「敬请期待」）
const tocItems = [
  { id: 'guide', title: '功能引导', file: '../docs/guide.md' },
  { id: 'qa-tutorial', title: '面试问答教学', file: '../docs/qa-tutorial.md' },
  { id: 'changelog', title: '更新日志', comingSoon: true },
  { id: 'help', title: '帮助文档', comingSoon: true },
  { id: 'feedback', title: '问题反馈', comingSoon: true }
]

const route = useRoute()
const router = useRouter()

const activeId = ref('')
const content = ref('')

function openDoc(id) {
  const item = tocItems.find((it) => it.id === id)
  if (!item || item.comingSoon) {
    return
  }
  activeId.value = id
  content.value = modules[item.file] || ''
  // 同步到 URL，支持刷新与分享直达
  if (route.query.doc !== id) {
    router.replace({ query: { doc: id } })
  }
}

// 渲染后的 HTML：marked 默认关闭 html，原始 HTML 会被转义，v-html 仅渲染自产标签
const renderedHtml = computed(() => (content.value ? marked.parse(content.value) : ''))

// URL 携带 doc 参数直达对应文档；缺省打开第一篇
const initial = tocItems.some((it) => it.id === route.query.doc && !it.comingSoon)
  ? route.query.doc
  : tocItems[0].id
openDoc(initial)

// 前进/后退切换文档时保持内容同步
watch(
  () => route.query.doc,
  (doc) => {
    if (route.name !== 'docs') {
      return
    }
    const target = tocItems.some((it) => it.id === doc && !it.comingSoon) ? doc : tocItems[0].id
    if (target !== activeId.value) {
      openDoc(target)
    }
  }
)
</script>

<template>
  <div class="page docs-page">
    <h1 class="page-title">文档</h1>
    <div class="docs-layout">
      <aside class="docs-toc card">
        <div class="toc-title">目录</div>
        <ul>
          <li
            v-for="item in tocItems"
            :key="item.id"
            :class="{ 'is-active': item.id === activeId, 'is-soon': item.comingSoon }"
            @click="openDoc(item.id)"
          >
            {{ item.title }}
            <span v-if="item.comingSoon" class="soon-tag">敬请期待</span>
          </li>
        </ul>
      </aside>
      <article class="docs-content card">
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="doc-body" v-html="renderedHtml"></div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.docs-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.docs-toc {
  width: 200px;
  flex-shrink: 0;
  padding: 16px 12px;
}

.toc-title {
  font-weight: 700;
  padding: 0 10px 10px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}

.docs-toc li {
  list-style: none;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-light);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.docs-toc li:hover {
  background: #f0f3fb;
}

.docs-toc li.is-active {
  background: #eef1ff;
  color: var(--primary);
  font-weight: 600;
}

.docs-toc li.is-soon {
  cursor: default;
  color: #aab2c0;
}

.docs-toc li.is-soon:hover {
  background: transparent;
}

.soon-tag {
  font-size: 11px;
  color: #aab2c0;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}

.docs-content {
  flex: 1;
  min-width: 0;
}

/* v-html 渲染内容需穿透 scoped 样式 */
.doc-body :deep(h1) {
  font-size: 22px;
  margin-bottom: 16px;
}

.doc-body :deep(h2) {
  font-size: 17px;
  margin: 24px 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
}

.doc-body :deep(h3) {
  font-size: 15px;
  margin: 18px 0 8px;
}

.doc-body :deep(p) {
  margin: 8px 0;
}

.doc-body :deep(ul),
.doc-body :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}

.doc-body :deep(li) {
  margin: 4px 0;
}

.doc-body :deep(blockquote) {
  border-left: 3px solid var(--primary);
  background: #f6f8ff;
  padding: 8px 14px;
  margin: 10px 0;
  border-radius: 0 8px 8px 0;
  color: var(--text-light);
}

.doc-body :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
}

.doc-body :deep(th),
.doc-body :deep(td) {
  border: 1px solid var(--border);
  padding: 7px 12px;
  text-align: left;
}

.doc-body :deep(th) {
  background: #f0f3fb;
}

.doc-body :deep(code) {
  background: #f0f3fb;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 13px;
}

@media (max-width: 767px) {
  .docs-layout {
    flex-direction: column;
  }

  .docs-toc {
    width: 100%;
  }
}
</style>
