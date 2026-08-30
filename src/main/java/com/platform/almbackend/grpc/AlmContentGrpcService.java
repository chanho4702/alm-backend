package com.platform.almbackend.grpc;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.proto.alm.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** search-service 전용 색인 원문 조달. 권한 없는 내부망 포트이므로 호스트에 공개하지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlmContentGrpcService extends AlmContentServiceGrpc.AlmContentServiceImplBase {
    private static final int BATCH = 200;

    private final IssueRepository issues;
    private final ProjectRepository projects;

    @Override
    @Transactional(readOnly = true)
    public void getIssueContent(GetIssueContentRequest request, StreamObserver<IssueContent> observer) {
        Optional<Issue> issue = issues.findById(request.getIssueId());
        if (issue.isEmpty()) {
            observer.onError(Status.NOT_FOUND
                    .withDescription("issue not found: " + request.getIssueId())
                    .asRuntimeException());
            return;
        }
        Optional<Project> project = projects.findById(issue.get().getProjectId());
        if (project.isEmpty()) {
            log.error("고아 이슈 — 프로젝트가 없다: issue={} project={}",
                    issue.get().getId(), issue.get().getProjectId());
            observer.onError(Status.FAILED_PRECONDITION
                    .withDescription("project missing for issue: " + request.getIssueId())
                    .asRuntimeException());
            return;
        }
        observer.onNext(toProto(issue.get(), project.get()));
        observer.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void listIssueContents(ListIssueContentsRequest request, StreamObserver<IssueContent> observer) {
        long projectFilter = request.getProjectId();
        Map<Long, Project> projectCache = new HashMap<>();
        long cursor = 0L;
        while (true) {
            List<Issue> batch = projectFilter == 0L
                    ? issues.findByIdGreaterThanOrderByIdAsc(cursor, Limit.of(BATCH))
                    : issues.findByProjectIdAndIdGreaterThanOrderByIdAsc(projectFilter, cursor, Limit.of(BATCH));
            if (batch.isEmpty()) break;
            for (Issue issue : batch) {
                Project project = projectCache.computeIfAbsent(
                        issue.getProjectId(), id -> projects.findById(id).orElse(null));
                if (project == null) {
                    log.warn("백필 중 고아 이슈 건너뜀: issue={} project={}", issue.getId(), issue.getProjectId());
                    continue;
                }
                observer.onNext(toProto(issue, project));
            }
            cursor = batch.getLast().getId();
        }
        observer.onCompleted();
    }

    private static IssueContent toProto(Issue issue, Project project) {
        IssueContent.Builder result = IssueContent.newBuilder()
                .setIssueId(issue.getId())
                .setProjectId(project.getId())
                .setProjectKey(project.getKey())
                .setProjectName(project.getName())
                .setIssueKey(issue.getKey())
                .setTitle(issue.getTitle())
                .setDescription(issue.getDescription())
                .setType(issue.getType())
                .setStatus(issue.getStatus())
                .setPriority(issue.getPriority())
                .setReporterId(issue.getReporterId())
                .setVersion(issue.getVersion())
                .setUpdatedAt(issue.getUpdatedAt().toEpochMilli());
        if (issue.getAssigneeId() != null) result.setAssigneeId(issue.getAssigneeId());
        return result.build();
    }
}

