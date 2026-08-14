package com.offerforge.ai;

import java.util.List;

public interface EmbeddingClient {

    List<Float> embed(String text);
}
