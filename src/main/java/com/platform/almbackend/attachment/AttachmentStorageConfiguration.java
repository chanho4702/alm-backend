package com.platform.almbackend.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;

/**
 * 저장소 선택. `platform.alm.storage.s3.enabled=true`면 S3 호환(MinIO), 아니면 로컬 파일.
 * 운영 compose는 S3를 켜고 MinIO 내부 DNS를 endpoint로 준다(호스트 비공개).
 */
@Configuration(proxyBeanMethods = false)
public class AttachmentStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.alm.storage.s3", name = "enabled", havingValue = "true")
    static class S3 {
        @Bean(destroyMethod = "close")
        S3Client almAttachmentS3Client(
                @Value("${platform.alm.storage.s3.region}") String region,
                @Value("${platform.alm.storage.s3.endpoint:}") String endpoint,
                @Value("${platform.alm.storage.s3.path-style-access:true}") boolean pathStyleAccess,
                @Value("${platform.alm.storage.s3.access-key:}") String accessKey,
                @Value("${platform.alm.storage.s3.secret-key:}") String secretKey) {
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(region))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(pathStyleAccess)
                            .build());
            if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
            if (!accessKey.isBlank() || !secretKey.isBlank()) {
                if (accessKey.isBlank() || secretKey.isBlank()) {
                    throw new IllegalArgumentException("S3 access-key와 secret-key는 함께 설정해야 합니다");
                }
                builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
            }
            return builder.build();
        }

        @Bean
        AttachmentStorage s3AttachmentStorage(
                S3Client almAttachmentS3Client,
                @Value("${platform.alm.storage.s3.bucket}") String bucket) {
            return new S3AttachmentStorage(almAttachmentS3Client, bucket);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AttachmentStorage.class)
    AttachmentStorage localAttachmentStorage(@Value("${platform.alm.storage.files-dir}") String filesDir) {
        return new LocalFileStorage(Path.of(filesDir));
    }
}
