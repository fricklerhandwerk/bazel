// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy.StopConcurrentSpawns;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestFileOutErr;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link DynamicSpawnStrategy}. */
@RunWith(JUnit4.class)
public class DynamicSpawnStrategyUnitTest {

  private static final SpawnResult SUCCESSFUL_SPAWN_RESULT =
      new SpawnResult.Builder().setRunnerName("test").setStatus(Status.SUCCESS).build();
  private static final FailureDetail FAILURE_DETAIL =
      FailureDetail.newBuilder().setExecution(Execution.getDefaultInstance()).build();

  private ExecutorService executorServiceForCleanup;

  @Mock private Function<Spawn, Optional<Spawn>> mockGetPostProcessingSpawn;
  @Mock private ExtendedEventHandler reporter;

  private Scratch scratch;
  private Path execDir;
  private ArtifactRoot rootDir;

  @Before
  public void initMocks() throws IOException {
    scratch = new Scratch();
    execDir = scratch.dir("/base/exec");
    rootDir = ArtifactRoot.asDerivedRoot(execDir, RootType.Output, "root");
    MockitoAnnotations.initMocks(this);
    // Mockito can't see that we want the function to return Optional.empty() instead
    // of null on apply by default (thanks generic type erasure). Set that up ourselves.
    when(mockGetPostProcessingSpawn.apply(any())).thenReturn(Optional.empty());
  }

  @After
  public void stopExecutorService() throws InterruptedException {
    if (executorServiceForCleanup != null) {
      executorServiceForCleanup.shutdown();
      assertThat(
              executorServiceForCleanup.awaitTermination(
                  TestUtils.WAIT_TIMEOUT_MILLISECONDS, MILLISECONDS))
          .isTrue();
    }
  }

  @Test
  public void exec_remoteOnlySpawn_doesNotExecLocalPostProcessingSpawn() throws Exception {
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> remoteSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(remote.exec(remoteSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    Spawn spawn = new SpawnBuilder().build();

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
    verify(mockGetPostProcessingSpawn, never()).apply(any());
    verify(local, never()).exec(any(), any(), any());
    assertThat(remoteSpawnCaptor.getAllValues()).containsExactly(spawn);
  }

  @Test
  public void exec_remoteOnlySpawn_noneCanExec_fails() throws Exception {
    Spawn spawn = new SpawnBuilder().withMnemonic("TheThing").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown).hasMessageThat().doesNotContain("dynamic_local_strategy");
    assertThat(thrown).hasMessageThat().containsMatch("\\bdynamic_remote_strategy\\b");
    assertThat(thrown).hasMessageThat().containsMatch("\\bTheThing\\b");
    verifyNoInteractions(local);
    // No post processing because local never ran.
    verify(mockGetPostProcessingSpawn, never()).apply(any());
  }

  @Test
  public void exec_localOnlySpawn_runsLocalPostProcessingSpawn() throws Exception {
    Spawn spawn = new SpawnBuilder("command").build();
    Spawn postProcessingSpawn = new SpawnBuilder("extra_command").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, ignored -> Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
    verifyNoInteractions(remote);
    assertThat(localSpawnCaptor.getAllValues())
        .containsExactly(spawn, postProcessingSpawn)
        .inOrder();
  }

  @Test
  public void exec_failedLocalSpawn_doesNotExecLocalPostProcessingSpawn() throws Exception {
    testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
        new SpawnResult.Builder()
            .setRunnerName("test")
            .setStatus(Status.TIMEOUT)
            .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
            .setFailureDetail(FAILURE_DETAIL)
            .build());
  }

  @Test
  public void exec_localOnlySpawn_noneCanExec_fails() throws Exception {
    Spawn spawn = new SpawnBuilder().withMnemonic("TheThing").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown).hasMessageThat().containsMatch("\\bdynamic_local_strategy\\b");
    assertThat(thrown).hasMessageThat().doesNotContain("dynamic_remote_strategy");
    assertThat(thrown).hasMessageThat().containsMatch("\\bTheThing\\b");
    verifyNoInteractions(remote);
    // No post processing because local never completed.
    verify(mockGetPostProcessingSpawn, never()).apply(any());
  }

  @Test
  public void exec_nonZeroExitCodeLocalSpawn_doesNotExecLocalPostProcessingSpawn()
      throws Exception {
    testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
        new SpawnResult.Builder()
            .setRunnerName("test")
            .setStatus(Status.EXECUTION_FAILED)
            .setExitCode(123)
            .setFailureDetail(FAILURE_DETAIL)
            .build());
  }

  private void testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(SpawnResult failedResult)
      throws Exception {
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(failedResult));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    Spawn spawn = new SpawnBuilder().build();

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(failedResult);
    assertThat(localSpawnCaptor.getAllValues()).containsExactly(spawn);
    verify(remote, never()).exec(any(), any(), any());
  }

  @Test
  public void exec_runAnywhereSpawn_runsLocalPostProcessingSpawn() throws Exception {
    Spawn spawn = new SpawnBuilder().build();
    Spawn postProcessingSpawn = new SpawnBuilder("extra_command").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.ANYWHERE, ignored -> Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /*stopConcurrentSpawns=*/ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    when(local.exec(eq(postProcessingSpawn), any(), /*stopConcurrentSpawns=*/ isNull()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
              throw new AssertionError("Timed out waiting for interruption");
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_runAnywhereSpawn_allowsIgnoringFailure() throws Exception {
    Spawn spawn = new SpawnBuilder().build();
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        new DynamicSpawnStrategy(
            executorServiceForCleanup,
            new DynamicExecutionOptions(),
            ignored -> ExecutionPolicy.ANYWHERE,
            ignored -> Optional.empty(),
            false,
            10,
            10,
            (s, exitCode, errorMsg, outErr, isLocal) -> isLocal && errorMsg.contains("Ignorable"));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /*stopConcurrentSpawns=*/ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(1, "Ignorable failure", null);
              // We should never get here, so return a different result from remote.
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              Thread.sleep(10);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_runAnywhereSpawn_notAlwaysIgnoringFailure() throws Exception {
    Spawn spawn = new SpawnBuilder().build();
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        new DynamicSpawnStrategy(
            executorServiceForCleanup,
            new DynamicExecutionOptions(),
            ignored -> ExecutionPolicy.ANYWHERE,
            ignored -> Optional.empty(),
            false,
            10,
            10,
            (s, exitCode, errorMsg, outErr, isLocal) -> isLocal && errorMsg.contains("Ignorable"));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    Semaphore localDone = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /*stopConcurrentSpawns=*/ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(1, "Not an ignorable failure", null);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              localDone.acquire();
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);
    localDone.release();
    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_localOnlySpawn_skipFirst_isRun() throws Exception {
    Spawn spawn = new SpawnBuilder().withMnemonic("TheThing").build();
    DynamicExecutionOptions options = new DynamicExecutionOptions();
    options.skipFirstBuild = true;
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, (s) -> Optional.empty(), options, true);

    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    when(local.exec(eq(spawn), any(), any())).thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    assertThat(dynamicSpawnStrategy.exec(spawn, actionExecutionContext))
        .containsExactly(SUCCESSFUL_SPAWN_RESULT);
    verifyNoInteractions(remote);
  }

  @Test
  public void exec_runAnywhereSpawn_skipFirst_onlyRemote() throws Exception {
    Spawn spawn = new SpawnBuilder().withMnemonic("TheThing").build();
    DynamicExecutionOptions options = new DynamicExecutionOptions();
    options.skipFirstBuild = true;
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.ANYWHERE, (s) -> Optional.empty(), options, true);

    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    assertThat(dynamicSpawnStrategy.exec(spawn, actionExecutionContext))
        .containsExactly(SUCCESSFUL_SPAWN_RESULT);
    verify(local, never()).exec(any(), any(), any());
    verify(reporter)
        .handle(
            Event.info(
                "Disabling dynamic execution until we have seen a successful build, see"
                    + " --experimental_dynamic_skip_first_build."));
  }

  @Test
  public void waitBranches_givesDebugOutputIfBothCancelled() throws Exception {
    Spawn spawn =
        new SpawnBuilder()
            .withOwnerPrimaryOutput(new SourceArtifact(rootDir, PathFragment.create("/foo"), null))
            .build();
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    AtomicReference<DynamicMode> strategyThatCancelled = new AtomicReference<>();
    DynamicExecutionOptions options = new DynamicExecutionOptions();
    LocalBranch localBranch =
        new LocalBranch(
            actionExecutionContext, spawn, strategyThatCancelled, options, null, null, null);
    RemoteBranch remoteBranch =
        new RemoteBranch(actionExecutionContext, spawn, strategyThatCancelled, options, null, null);
    localBranch.prepareFuture(remoteBranch);
    remoteBranch.prepareFuture(localBranch);
    localBranch.cancel();
    remoteBranch.cancel();
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                DynamicSpawnStrategy.waitBranches(
                    localBranch,
                    remoteBranch,
                    spawn,
                    new DynamicExecutionOptions(),
                    actionExecutionContext));
    assertThat(error).hasMessageThat().contains("Neither branch of /foo completed.");
  }

  @Test
  public void exec_runAnywhereSpawn_localCantExec_runsRemote() throws Exception {
    Spawn spawn = new SpawnBuilder().build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(0, "", null);
              }
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
    // Never runs anything as it says it can't execute anything at all.
    verify(local, never()).exec(any(), any(), any());
    verify(mockGetPostProcessingSpawn, never()).apply(any());
  }

  @Test
  public void exec_runAnywhereSpawn_remoteCantExec_runsLocal() throws Exception {
    Spawn spawn = new SpawnBuilder().build();
    Spawn postProcessingSpawn = new SpawnBuilder("extra_command").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.ANYWHERE, ignored -> Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenAnswer(
            invocation -> {
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(0, "", null);
              }
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
    assertThat(localSpawnCaptor.getAllValues())
        .containsExactly(spawn, postProcessingSpawn)
        .inOrder();
    verify(remote, never()).exec(any(), any(), any());
  }

  @Test
  public void exec_runAnywhereSpawn_noneCanExec_fails() throws Exception {
    Spawn spawn = new SpawnBuilder().withMnemonic("TheThing").build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown).hasMessageThat().containsMatch("\\bdynamic_local_strategy\\b");
    assertThat(thrown).hasMessageThat().containsMatch("\\bdynamic_remote_strategy\\b");
    assertThat(thrown).hasMessageThat().containsMatch("\\bTheThing\\b");
    // No post processing because local never completed.
    verify(mockGetPostProcessingSpawn, never()).apply(any());
  }

  private DynamicSpawnStrategy createDynamicSpawnStrategy(
      ExecutionPolicy executionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution) {
    return createDynamicSpawnStrategy(
        executionPolicy,
        getPostProcessingSpawnForLocalExecution,
        new DynamicExecutionOptions(),
        false);
  }

  private DynamicSpawnStrategy createDynamicSpawnStrategy(
      ExecutionPolicy executionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution,
      DynamicExecutionOptions options,
      boolean isFirst) {
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    return new DynamicSpawnStrategy(
        executorServiceForCleanup,
        options,
        ignored -> executionPolicy,
        getPostProcessingSpawnForLocalExecution,
        isFirst,
        10,
        10,
        null);
  }

  private static ActionExecutionContext createMockActionExecutionContext(
      SandboxedSpawnStrategy localStrategy, SandboxedSpawnStrategy remoteStrategy) {
    ActionExecutionContext actionExecutionContext = mock(ActionExecutionContext.class);
    when(actionExecutionContext.getFileOutErr()).thenReturn(new TestFileOutErr());
    when(actionExecutionContext.getContext(DynamicStrategyRegistry.class))
        .thenReturn(
            new DynamicStrategyRegistry() {
              @Override
              public ImmutableList<SandboxedSpawnStrategy> getDynamicSpawnActionContexts(
                  Spawn spawn, DynamicMode dynamicMode) {
                switch (dynamicMode) {
                  case LOCAL:
                    return ImmutableList.of(localStrategy);
                  case REMOTE:
                    return ImmutableList.of(remoteStrategy);
                }
                throw new AssertionError("Unexpected mode: " + dynamicMode);
              }

              @Override
              public void notifyUsedDynamic(ActionContextRegistry actionContextRegistry) {}
            });
    when(actionExecutionContext.withFileOutErr(any())).thenReturn(actionExecutionContext);
    return actionExecutionContext;
  }

  private static SandboxedSpawnStrategy createMockSpawnStrategy()
      throws InterruptedException, ExecException {
    return createMockSpawnStrategy(true);
  }

  private static SandboxedSpawnStrategy createMockSpawnStrategy(boolean canExec)
      throws InterruptedException {
    SandboxedSpawnStrategy strategy = mock(SandboxedSpawnStrategy.class);
    when(strategy.canExec(any(), any())).thenReturn(canExec);
    when(strategy.beginExecution(any(), any())).thenThrow(UnsupportedOperationException.class);
    return strategy;
  }
}
