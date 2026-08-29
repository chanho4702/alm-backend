package com.platform.almbackend.attachment;

import com.platform.almbackend.common.ServiceUnavailableException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.UUID;

/** S3 호환 저장소(운영: MinIO). wiki-backend의 S3AttachmentStorage와 같은 규약이다. */
public class S3AttachmentStorage implements AttachmentStorage {
    private final S3Client client;
    private final String bucket;

    public S3AttachmentStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public StoredObject store(InputStream input, long contentLength, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(), RequestBody.fromInputStream(input, contentLength));
            return new StoredObject(bucket, key);
        } catch (RuntimeException e) {
            // 스토리지 장애는 권한 문제가 아니다 — 503으로 올려 화면이 "다시 시도"를 안내하게 한다
            throw new ServiceUnavailableException("첨부 저장소에 연결할 수 없습니다");
        }
    }

    @Override
    public Resource open(String bucket, String key) {
        try {
            ResponseInputStream<GetObjectResponse> input = client.getObject(
                    GetObjectRequest.builder().bucket(requireBucket(bucket)).key(key).build());
            long contentLength = input.response().contentLength();
            return new InputStreamResource(input) {
                @Override
                public long contentLength() {
                    return contentLength;
                }
            };
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("첨부 저장소에 연결할 수 없습니다");
        }
    }

    @Override
    public boolean delete(String bucket, String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(requireBucket(bucket)).key(key).build());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("첨부 bucket 메타데이터가 없습니다");
        }
        return bucket;
    }
}
