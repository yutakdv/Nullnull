import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { components } from '@nullnull/api-client';
import { getApiClient } from './session.js';
import { toProblem } from './problem.js';

export function useCreatePostImageUpload() {
  return useMutation({
    mutationFn: async ({
      request,
      idempotencyKey,
    }: {
      request: components['schemas']['CreateUploadRequest'];
      idempotencyKey: string;
    }) => {
      const { data, error, response } = await getApiClient().POST(
        '/posts/images/uploads',
        {
          params: { header: { 'Idempotency-Key': idempotencyKey } },
          body: request,
        },
      );
      if (!data)
        throw (
          toProblem(error) ??
          new Error(`Upload reservation failed (${String(response.status)})`)
        );
      return data;
    },
  });
}

export function useCreatePost() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      request,
      idempotencyKey,
    }: {
      request: components['schemas']['CreatePostRequest'];
      idempotencyKey: string;
    }) => {
      const { data, error, response } = await getApiClient().POST('/posts', {
        params: { header: { 'Idempotency-Key': idempotencyKey } },
        body: request,
      });
      if (!data)
        throw (
          toProblem(error) ?? new Error(`Publishing failed (${String(response.status)})`)
        );
      return data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['feed'] });
    },
  });
}
