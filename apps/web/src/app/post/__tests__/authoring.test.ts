import { describe, expect, it, vi } from 'vitest';
import { validatePostImage, uploadPostImage } from '../authoring.js';

describe('post image boundary', () => {
  it('accepts only nonempty JPEG/PNG at or below the contract ceiling', () => {
    expect(validatePostImage({ type: 'image/png', size: 4194304 })).toBeNull();
    expect(validatePostImage({ type: 'image/webp', size: 10 })).toBe('format');
    expect(validatePostImage({ type: 'image/jpeg', size: 0 })).toBe('size');
    expect(validatePostImage({ type: 'image/jpeg', size: 4194305 })).toBe('size');
  });
  it('refuses expired tickets without transmitting a file', async () => {
    const factory = vi.fn();
    await expect(
      uploadPostImage(
        new Blob(['a']),
        {
          uploadId: 'id',
          url: 'https://storage.example.test/upload',
          method: 'PUT',
          headers: {},
          expiresAt: '2000-01-01T00:00:00Z',
        },
        new AbortController().signal,
        vi.fn(),
        factory,
      ),
    ).rejects.toThrow('expired');
    expect(factory).not.toHaveBeenCalled();
  });
  it('uses signed headers, no credentials, real progress, and supports cancellation', async () => {
    const headers: Record<string, string> = {};
    const xhr = {
      open: vi.fn(),
      setRequestHeader: vi.fn((key: string, value: string) => {
        headers[key] = value;
      }),
      upload: {},
      send: vi.fn(),
      abort: vi.fn(),
      withCredentials: true,
      status: 200,
    } as unknown as XMLHttpRequest;
    const controller = new AbortController();
    const progress = vi.fn();
    const pending = uploadPostImage(
      new Blob(['a']),
      {
        uploadId: 'id',
        url: 'https://storage.example.test/upload',
        method: 'PUT',
        headers: { 'Content-Type': 'image/png', 'x-amz-checksum-sha256': 'signed' },
        expiresAt: '2099-01-01T00:00:00Z',
      },
      controller.signal,
      progress,
      () => xhr,
    );
    expect(xhr.open).toHaveBeenCalledWith('PUT', 'https://storage.example.test/upload');
    expect(xhr.withCredentials).toBe(false);
    expect(headers).toEqual({
      'Content-Type': 'image/png',
      'x-amz-checksum-sha256': 'signed',
    });
    xhr.upload.onprogress?.call(xhr, {
      lengthComputable: true,
      loaded: 1,
      total: 2,
    } as ProgressEvent);
    expect(progress).toHaveBeenCalledWith(50);
    controller.abort();
    xhr.onabort?.call(xhr, {} as ProgressEvent);
    await expect(pending).rejects.toThrow();
    expect(xhr.abort).toHaveBeenCalled();
  });
});
