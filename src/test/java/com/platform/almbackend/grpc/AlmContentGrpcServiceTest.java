package com.platform.almbackend.grpc;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.proto.alm.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class AlmContentGrpcServiceTest {
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;

    Server server;
    ManagedChannel channel;
    AlmContentServiceGrpc.AlmContentServiceBlockingStub stub;

    @BeforeEach
    void setup() throws IOException {
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new AlmContentGrpcService(issues, projects))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = AlmContentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(3, TimeUnit.SECONDS);
        server.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    void 이슈와_프로젝트_표시값을_함께_돌려준다() {
        Project project = projects.save(Project.of("ALM", "ALM 제품", "설명"));
        Issue issue = issues.save(Issue.of(project.getId(), 1, "ALM-1", "로그인 오류",
                "OIDC callback 실패", IssueType.BUG, "todo", IssuePriority.HIGH, 2L, 1L));
        issues.flush();

        IssueContent result = stub.getIssueContent(
                GetIssueContentRequest.newBuilder().setIssueId(issue.getId()).build());

        assertThat(result.getProjectKey()).isEqualTo("ALM");
        assertThat(result.getProjectName()).isEqualTo("ALM 제품");
        assertThat(result.getIssueKey()).isEqualTo("ALM-1");
        assertThat(result.getDescription()).contains("callback");
        assertThat(result.hasAssigneeId()).isTrue();
        assertThat(result.getUpdatedAt()).isPositive();
    }

    @Test
    void 없는_이슈는_NOT_FOUND다() {
        assertThatThrownBy(() -> stub.getIssueContent(
                GetIssueContentRequest.newBuilder().setIssueId(999L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void 백필_스트림은_프로젝트로_좁힐_수_있다() {
        Project a = projects.save(Project.of("AAA", "A", ""));
        Project b = projects.save(Project.of("BBB", "B", ""));
        issues.save(Issue.of(a.getId(), 1, "AAA-1", "A 이슈", "", IssueType.TASK,
                "todo", IssuePriority.MEDIUM, null, 1L));
        issues.save(Issue.of(b.getId(), 1, "BBB-1", "B 이슈", "", IssueType.TASK,
                "todo", IssuePriority.MEDIUM, null, 1L));
        issues.flush();

        List<IssueContent> result = drain(stub.listIssueContents(
                ListIssueContentsRequest.newBuilder().setProjectId(b.getId()).build()));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getIssueKey()).isEqualTo("BBB-1");
        assertThat(result.getFirst().hasAssigneeId()).isFalse();
    }

    private static <T> List<T> drain(java.util.Iterator<T> iterator) {
        List<T> result = new ArrayList<>();
        iterator.forEachRemaining(result::add);
        return result;
    }
}

