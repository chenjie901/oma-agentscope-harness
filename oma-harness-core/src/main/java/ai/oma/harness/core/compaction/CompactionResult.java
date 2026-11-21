package ai.oma.harness.core.compaction;

import io.agentscope.core.message.Msg;

import java.util.List;

/** 一次压缩的结果：压缩后的消息列表、摘要文本与被压缩掉的原始消息条数。 */
public record CompactionResult(List<Msg> messages, String summary, int droppedMessages) {
}
