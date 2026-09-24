import { assembleFrame, buildFrames, FRAME_CHUNK_SIZE, type AiRequestPayload } from '../shared/telegram';

describe('buildFrames', () => {
  it('produces a single chunk for a small payload', () => {
    const payload: AiRequestPayload = {
      kind: 'chat',
      system: 'be helpful',
      userMessage: 'hi',
      outputSchema: { type: 'object' },
    };
    const frames = buildFrames('ai_request', 'user-1', 'req-1', payload);
    expect(frames).toHaveLength(1);
    expect(frames[0]).toMatchObject({ v: 1, type: 'ai_request', userId: 'user-1', reqId: 'req-1', seq: 0, totalChunks: 1 });
    expect(JSON.parse(frames[0].chunk)).toEqual(payload);
  });

  it('splits a payload larger than FRAME_CHUNK_SIZE into multiple ordered chunks', () => {
    const payload: AiRequestPayload = {
      kind: 'plan',
      system: 'x'.repeat(FRAME_CHUNK_SIZE * 2 + 500), // forces at least 3 chunks
      userMessage: 'plan my week',
      outputSchema: { type: 'object' },
    };
    const frames = buildFrames('ai_request', 'user-1', 'req-2', payload);
    expect(frames.length).toBeGreaterThan(1);
    frames.forEach((frame, i) => {
      expect(frame.seq).toBe(i);
      expect(frame.totalChunks).toBe(frames.length);
      expect(frame.chunk.length).toBeLessThanOrEqual(FRAME_CHUNK_SIZE);
    });

    // Reassembling in send order (what assembleFrame does, chunk by chunk)
    // must reproduce the exact original payload.
    const joined = frames.map((f) => f.chunk).join('');
    expect(JSON.parse(joined)).toEqual(payload);
  });

  it('still produces exactly one (empty) chunk for a falsy payload', () => {
    const frames = buildFrames('heartbeat', 'user-1', undefined, undefined);
    expect(frames).toHaveLength(1);
    expect(frames[0].chunk).toBe('');
  });
});

describe('assembleFrame', () => {
  it('short-circuits a single-chunk frame without touching DynamoDB', async () => {
    const payload = { ollamaOk: true, model: 'qwen2.5:7b-instruct' };
    const [frame] = buildFrames('heartbeat', 'user-1', undefined, payload);
    // No DynamoDB table/credentials are configured in this test environment
    // — if assembleFrame tried to call out for a totalChunks===1 frame,
    // this would hang or throw, not just return the wrong value.
    await expect(assembleFrame(frame)).resolves.toEqual(payload);
  });
});
