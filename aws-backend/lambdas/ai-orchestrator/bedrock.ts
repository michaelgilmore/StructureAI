import { BedrockRuntimeClient, ConverseCommand, type Message } from '@aws-sdk/client-bedrock-runtime';

const client = new BedrockRuntimeClient({});

/**
 * Defaults to the cross-region inference profile for Claude Sonnet 4.5 —
 * required because several current Claude models on Bedrock reject
 * on-demand InvokeModel/Converse calls against the bare foundation-model id
 * and ask for an inference-profile id instead. Override via
 * BEDROCK_MODEL_ID (infra/lib/ai-stack.ts sets it) if you want a different
 * model, e.g. the cheaper `us.anthropic.claude-haiku-4-5-20251001-v1:0`, or
 * once Claude Sonnet 4.6's exact Bedrock model id is confirmed in your
 * account's model catalog — see aws-backend/README.md's verification note
 * on this, and ../../docs/architecture.md §03 (Sonnet/Haiku named as
 * examples there, not pinned to one).
 */
const MODEL_ID = process.env.BEDROCK_MODEL_ID ?? 'us.anthropic.claude-sonnet-4-5-20250929-v1:0';

export interface BedrockJsonCall {
  system: string;
  /** Prior turns, oldest first, NOT including the new user message. */
  history?: Message[];
  userMessage: string;
  toolName: string;
  toolDescription: string;
  /**
   * JSON Schema (draft-07-ish subset Bedrock's Converse API accepts)
   * describing the tool's input. Typed `Record<string, any>` rather than
   * `Record<string, unknown>` on purpose — it flows into `ConverseCommand`'s
   * `toolConfig.tools[].toolSpec.inputSchema.json`, which the SDK types as
   * the recursive `DocumentType` union; `unknown` values can't structurally
   * satisfy that (every value in a nested schema object would need to be
   * provably a `DocumentType`), so this is one of the few places `any` is
   * the right call rather than a workaround for a real type-safety gap.
   */
  inputSchema: Record<string, any>;
  maxTokens?: number;
}

/**
 * Calls Bedrock's Converse API with a single tool and `toolChoice` forcing
 * that exact tool, so the model's reply is (supposed to be) already the
 * structured JSON object described by `inputSchema` — no "parse JSON out of
 * a markdown code fence" step. This is the mechanism
 * ../../docs/architecture.md §07 means by "the model returns a strict JSON
 * shape" and §03's "the orchestrator validates every response against the
 * schema" (this function does the Bedrock-call half; callers still validate
 * the returned shape against their own zod schema before trusting it as
 * that's cheap insurance and Bedrock's own docs record at least one
 * real-world case of a forced-tool-choice call landing an empty `input`
 * object instead of throwing — see aws-backend/README.md's verification
 * note).
 *
 * `toolChoice: {tool: {name}}` (AWS's `SpecificToolChoice`) is documented as
 * supported for Anthropic Claude models specifically — verified against
 * AWS's API reference before writing this, given this session's history of
 * bugs from guessed AWS SDK shapes.
 */
export async function callBedrockTool<T>(opts: BedrockJsonCall): Promise<T> {
  const messages: Message[] = [
    ...(opts.history ?? []),
    { role: 'user', content: [{ text: opts.userMessage }] },
  ];

  const response = await client.send(new ConverseCommand({
    modelId: MODEL_ID,
    system: [{ text: opts.system }],
    messages,
    toolConfig: {
      tools: [{
        toolSpec: {
          name: opts.toolName,
          description: opts.toolDescription,
          inputSchema: { json: opts.inputSchema },
        },
      }],
      toolChoice: { tool: { name: opts.toolName } },
    },
    inferenceConfig: { maxTokens: opts.maxTokens ?? 2000, temperature: 0.4 },
  }));

  const content = response.output?.message?.content ?? [];
  const toolUseBlock = content.find((block) => block.toolUse !== undefined);

  if (!toolUseBlock?.toolUse) {
    const textBlock = content.find((block) => typeof block.text === 'string');
    throw new Error(
      `Bedrock did not return a ${opts.toolName} tool call (stopReason=${response.stopReason ?? 'unknown'})` +
        (textBlock?.text ? `: ${textBlock.text.slice(0, 300)}` : ''),
    );
  }

  const input = toolUseBlock.toolUse.input;
  if (input === undefined || input === null || (typeof input === 'object' && Object.keys(input).length === 0)) {
    // See the doc comment above — a known (if apparently rare) Bedrock
    // Converse behavior with forced tool choice is an empty `{}` input
    // instead of a thrown error. Treat it the same as "didn't answer" so
    // the caller marks the request failed instead of persisting garbage.
    throw new Error(`Bedrock returned an empty input for ${opts.toolName} — treating as a failed call`);
  }

  return input as T;
}
