package com.vlkan.rfos.policy;

import com.vlkan.rfos.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;


import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CdrRotationPolicyTest {
  private static final Logger LOGGER = LogManager.getLogger(CdrRotationPolicyTest.class);

  @Test
  void test() {

    // Create the scheduler mock.
    ScheduledFuture<?> scheduledFuture = Mockito.mock(ScheduledFuture.class);
    ScheduledExecutorService executorService = Mockito.mock(ScheduledExecutorService.class);
    Mockito
        .when(executorService.schedule(
            Mockito.any(Runnable.class),
            Mockito.anyLong(),
            Mockito.same(TimeUnit.MILLISECONDS)))
        .thenAnswer(new Answer<ScheduledFuture<?>>() {

          private int invocationCount = 0;

          @Override
          public ScheduledFuture<?> answer(InvocationOnMock invocation) {
            Runnable runnable = invocation.getArgument(0);
            if (++invocationCount < 5) {
              runnable.run();
            } else {
              LOGGER.trace("skipping execution {invocationCount={}}", invocationCount);
            }
            return scheduledFuture;
          }

        });

    // Create the clock mock.
    Clock clock = Mockito.mock(Clock.class);
    Instant midnight1 = Instant.parse("2017-12-29T00:00:00.000Z");
    Mockito
        .when(clock.now())
        .thenReturn(midnight1.plus(Duration.ofSeconds(30)))
        .thenReturn(midnight1.plus(Duration.ofSeconds(60)))
        .thenReturn(midnight1.plus(Duration.ofSeconds(120)))
        .thenReturn(midnight1.plus(Duration.ofSeconds(180)))
        .thenReturn(midnight1.plus(Duration.ofSeconds(190)))
        .thenReturn(midnight1.plus(Duration.ofSeconds(300)));
//    Mockito
//        .when(clock.midnight())
//        .thenReturn(midnight1)
//        .thenReturn(midnight2);

    // Create the config.
    CdrRotationPolicy policy = new CdrRotationPolicy(30);
    File file = Mockito.mock(File.class);
    RotatingFilePattern filePattern = Mockito.mock(RotatingFilePattern.class);
    RotationConfig config = RotationConfig
        .builder()
        .file(file)
        .filePattern(filePattern)
        .clock(clock)
        .executorService(executorService)
        .policy(policy)
        .build();

    // Create the rotatable mock.
    Rotatable rotatable = Mockito.mock(Rotatable.class);
    Mockito.when(rotatable.getConfig()).thenReturn(config);

    // Start policy.
    policy.start(rotatable);

    // Verify the 1st execution.
//    Mockito
//        .verify(executorService)
//        .schedule(
//            Mockito.any(Runnable.class),
//            Mockito.eq(waitPeriod1Millis),
//            Mockito.same(TimeUnit.MILLISECONDS));
//
//    // Verify the 1st rotation.
//    Mockito
//        .verify(rotatable)
//        .rotate(Mockito.same(policy), Mockito.eq(midnight1));
//
//    // Verify the 2nd execution.
//    Mockito
//        .verify(executorService, Mockito.atLeastOnce())
//        .schedule(
//            Mockito.any(Runnable.class),
//            Mockito.eq(waitPeriod2Millis),
//            Mockito.same(TimeUnit.MILLISECONDS));
//
//    // Verify the 2nd rotation.
//    Mockito
//        .verify(rotatable)
//        .rotate(Mockito.same(policy), Mockito.eq(midnight2));

    // Close the policy.
    policy.stop();

    // Verify the task cancellation.
    Mockito
        .verify(scheduledFuture)
        .cancel(Mockito.same(true));

  }
}
