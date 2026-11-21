package ai.oma.harness.core.compaction;

import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/** 摘要提供者，由 core 用 OmaChatModel 实现，把压缩器与具体模型调用解耦。 */
@FunctionalInterface
public interface SummaryProvider {
    Mono<String> summarize(List<Msg> prefix, String prompt);
}
