package com.platform.almbackend.attachment;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 첨부 업로드 한도 — 파일 한도({@code spring.servlet.multipart.max-file-size} = ALM_MAX_ATTACHMENT_MB) 하나만
 * 설정하고, 요청 한도는 거기에 multipart 경계·헤더 몫 1MB를 더해 파생한다. 둘을 같게 두면 한도와 딱 맞는
 * 파일이 요청 한도에 먼저 걸린다. nginx의 ALM 첨부 경로 client_max_body_size(21m)도 같은 계산이다.
 */
@Configuration
public class AttachmentUploadLimits {
    static final DataSize MULTIPART_OVERHEAD = DataSize.ofMegabytes(1);

    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties properties) {
        DataSize file = properties.getMaxFileSize();
        MultipartProperties derived = new MultipartProperties();
        derived.setEnabled(properties.isEnabled());
        derived.setLocation(properties.getLocation());
        derived.setFileSizeThreshold(properties.getFileSizeThreshold());
        derived.setResolveLazily(properties.isResolveLazily());
        derived.setStrictServletCompliance(properties.isStrictServletCompliance());
        derived.setMaxFileSize(file);
        derived.setMaxRequestSize(file.isNegative()
                ? file
                : DataSize.ofBytes(file.toBytes() + MULTIPART_OVERHEAD.toBytes()));
        return derived.createMultipartConfig();
    }

    /** 한도 초과 — 공통 오류 계약 그대로 413 {"error"}. 프론트는 같은 문구로 사전 검증한다 */
    @RestControllerAdvice
    static class TooLargeHandler {
        private final DataSize maxFileSize;

        TooLargeHandler(MultipartProperties properties) {
            this.maxFileSize = properties.getMaxFileSize();
        }

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
        public Map<String, String> tooLarge(MaxUploadSizeExceededException e) {
            return Map.of("error", "첨부 파일은 " + maxFileSize.toMegabytes() + "MB까지 올릴 수 있습니다");
        }
    }
}
