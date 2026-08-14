package com.offerforge.ai;

import java.io.IOException;

@FunctionalInterface
public interface AiStreamChunkConsumer {

    void accept(String chunk) throws IOException;
}
