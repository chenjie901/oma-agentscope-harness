package ai.oma.harness.core.compaction;

import java.util.Set;

/** 上下文压缩配置，字段语义对齐 AgentScope 原生 CompactionConfig，裁剪了 OMA 不需要的 memory flush / offload / 独立摘要模型部分。 */
public final class CompactionConfig {
    public static final int FALLBACK_TRIGGER_TOKENS = 160_000;

    public static final String DEFAULT_SUMMARY_PROMPT = """
            <role>
            Context Extraction Assistant
            </role>

            <primary_objective>
            Your sole objective in this task is to extract the highest quality/most relevant \
            context from the conversation history below.
            </primary_objective>

            <objective_information>
            You're nearing the total number of input tokens you can accept, so you must extract \
            the highest quality/most relevant pieces of information from your conversation history.
            This context will then overwrite the conversation history presented below. Because of \
            this, ensure the context you extract is only the most important information to \
            continue working toward your overall goal.
            </objective_information>

            <instructions>
            The conversation history below will be replaced with the context you extract in this \
            step. You want to ensure that you don't repeat any actions you've already completed, \
            so the context you extract from the conversation history should be focused on the \
            most important information to your overall goal.

            Structure your summary using these sections (populate each or write "None"):

            ## SESSION INTENT
            What is the user's primary goal or request?

            ## SUMMARY
            The most important context, decisions, reasoning, and rejected options.

            ## ARTIFACTS
            Files or resources created, modified, or accessed (with specific paths and changes).

            ## NEXT STEPS
            Specific tasks remaining to achieve the session intent.
            </instructions>

            Carefully read through the entire conversation history below and extract the most \
            important context. Respond ONLY with the extracted context.

            <messages>
            {messages}
            </messages>\
            """;

    private final int triggerMessages;
    private final int triggerTokens;
    private final int reserved;
    private final int keepMessages;
    private final int keepTokens;
    private final int keepTokensMin;
    private final int keepTokensMax;
    private final double keepTokensRatio;
    private final String summaryPrompt;
    private final TruncateArgsConfig truncateArgsConfig;
    private final PruneConfig pruneConfig;

    private CompactionConfig(Builder b) {
        this.triggerMessages = b.triggerMessages;
        this.triggerTokens = b.triggerTokens;
        this.reserved = b.reserved;
        this.keepMessages = b.keepMessages;
        this.keepTokens = b.keepTokens;
        this.keepTokensMin = b.keepTokensMin;
        this.keepTokensMax = b.keepTokensMax;
        this.keepTokensRatio = b.keepTokensRatio;
        this.summaryPrompt = b.summaryPrompt;
        this.truncateArgsConfig = b.truncateArgsConfig;
        this.pruneConfig = b.pruneConfig;
    }

    public static CompactionConfig defaults() {
        return builder().build();
    }

    public int triggerMessages() {
        return triggerMessages;
    }

    public int triggerTokens() {
        return triggerTokens;
    }

    public int reserved() {
        return reserved;
    }

    public int keepMessages() {
        return keepMessages;
    }

    public int keepTokens() {
        return keepTokens;
    }

    public int keepTokensMin() {
        return keepTokensMin;
    }

    public int keepTokensMax() {
        return keepTokensMax;
    }

    public double keepTokensRatio() {
        return keepTokensRatio;
    }

    public String summaryPrompt() {
        return summaryPrompt;
    }

    public TruncateArgsConfig truncateArgsConfig() {
        return truncateArgsConfig;
    }

    public PruneConfig pruneConfig() {
        return pruneConfig;
    }

    /** 用模型上下文窗口解析动态阈值后生成一份生效配置。 */
    public CompactionConfig withEffective(int effectiveTriggerTokens, int effectiveKeepTokens) {
        Builder b = new Builder();
        b.triggerMessages = this.triggerMessages;
        b.triggerTokens = effectiveTriggerTokens;
        b.reserved = this.reserved;
        b.keepMessages = this.keepMessages;
        b.keepTokens = effectiveKeepTokens;
        b.keepTokensMin = this.keepTokensMin;
        b.keepTokensMax = this.keepTokensMax;
        b.keepTokensRatio = this.keepTokensRatio;
        b.summaryPrompt = this.summaryPrompt;
        b.truncateArgsConfig = this.truncateArgsConfig;
        b.pruneConfig = this.pruneConfig;
        return new CompactionConfig(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 压缩配置构造器。 */
    public static final class Builder {
        private int triggerMessages = 50;
        private int triggerTokens = 0;
        private int reserved = 20_000;
        private int keepMessages = 20;
        private int keepTokens = -1;
        private int keepTokensMin = 2_000;
        private int keepTokensMax = 8_000;
        private double keepTokensRatio = 0.25;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private TruncateArgsConfig truncateArgsConfig = null;
        private PruneConfig pruneConfig = PruneConfig.defaults();

        public Builder triggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
            return this;
        }

        public Builder triggerTokens(int triggerTokens) {
            this.triggerTokens = triggerTokens;
            return this;
        }

        public Builder reserved(int reserved) {
            this.reserved = reserved;
            return this;
        }

        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        public Builder keepTokens(int keepTokens) {
            this.keepTokens = keepTokens;
            return this;
        }

        public Builder keepTokensMin(int keepTokensMin) {
            this.keepTokensMin = keepTokensMin;
            return this;
        }

        public Builder keepTokensMax(int keepTokensMax) {
            this.keepTokensMax = keepTokensMax;
            return this;
        }

        public Builder keepTokensRatio(double keepTokensRatio) {
            this.keepTokensRatio = keepTokensRatio;
            return this;
        }

        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = summaryPrompt;
            return this;
        }

        public Builder truncateArgs(TruncateArgsConfig truncateArgsConfig) {
            this.truncateArgsConfig = truncateArgsConfig;
            return this;
        }

        public Builder prune(PruneConfig pruneConfig) {
            this.pruneConfig = pruneConfig;
            return this;
        }

        public CompactionConfig build() {
            return new CompactionConfig(this);
        }
    }

    /** 摘要前对超大工具入参做轻量字符串截断的配置。 */
    public static final class TruncateArgsConfig {
        private final int triggerMessages;
        private final int triggerTokens;
        private final int keepMessages;
        private final int keepTokens;
        private final int maxArgLength;
        private final String truncationText;

        private TruncateArgsConfig(Builder b) {
            this.triggerMessages = b.triggerMessages;
            this.triggerTokens = b.triggerTokens;
            this.keepMessages = b.keepMessages;
            this.keepTokens = b.keepTokens;
            this.maxArgLength = b.maxArgLength;
            this.truncationText = b.truncationText;
        }

        public int triggerMessages() {
            return triggerMessages;
        }

        public int triggerTokens() {
            return triggerTokens;
        }

        public int keepMessages() {
            return keepMessages;
        }

        public int keepTokens() {
            return keepTokens;
        }

        public int maxArgLength() {
            return maxArgLength;
        }

        public String truncationText() {
            return truncationText;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private int triggerMessages = 25;
            private int triggerTokens = 40_000;
            private int keepMessages = 20;
            private int keepTokens = 0;
            private int maxArgLength = 2_000;
            private String truncationText = "...(argument truncated)";

            public Builder triggerMessages(int triggerMessages) {
                this.triggerMessages = triggerMessages;
                return this;
            }

            public Builder triggerTokens(int triggerTokens) {
                this.triggerTokens = triggerTokens;
                return this;
            }

            public Builder keepMessages(int keepMessages) {
                this.keepMessages = keepMessages;
                return this;
            }

            public Builder keepTokens(int keepTokens) {
                this.keepTokens = keepTokens;
                return this;
            }

            public Builder maxArgLength(int maxArgLength) {
                this.maxArgLength = maxArgLength;
                return this;
            }

            public Builder truncationText(String truncationText) {
                this.truncationText = truncationText;
                return this;
            }

            public TruncateArgsConfig build() {
                return new TruncateArgsConfig(this);
            }
        }
    }

    /** 工具结果聚合裁剪配置。 */
    public static final class PruneConfig {
        private final int protectTokens;
        private final int minimumTokens;
        private final int maxOutputChars;
        private final Set<String> excludedTools;

        private PruneConfig(Builder b) {
            this.protectTokens = b.protectTokens;
            this.minimumTokens = b.minimumTokens;
            this.maxOutputChars = b.maxOutputChars;
            this.excludedTools = Set.copyOf(b.excludedTools);
        }

        public static PruneConfig defaults() {
            return builder().build();
        }

        public int protectTokens() {
            return protectTokens;
        }

        public int minimumTokens() {
            return minimumTokens;
        }

        public int maxOutputChars() {
            return maxOutputChars;
        }

        public Set<String> excludedTools() {
            return excludedTools;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private int protectTokens = 40_000;
            private int minimumTokens = 20_000;
            private int maxOutputChars = 2_000;
            private Set<String> excludedTools =
                    Set.of("read_file", "memory_search", "memory_get", "session_search");

            public Builder protectTokens(int protectTokens) {
                this.protectTokens = protectTokens;
                return this;
            }

            public Builder minimumTokens(int minimumTokens) {
                this.minimumTokens = minimumTokens;
                return this;
            }

            public Builder maxOutputChars(int maxOutputChars) {
                this.maxOutputChars = maxOutputChars;
                return this;
            }

            public Builder excludedTools(Set<String> excludedTools) {
                this.excludedTools = excludedTools;
                return this;
            }

            public PruneConfig build() {
                return new PruneConfig(this);
            }
        }
    }
}
