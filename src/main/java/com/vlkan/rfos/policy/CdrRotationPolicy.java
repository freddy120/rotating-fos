package com.vlkan.rfos.policy;

import com.vlkan.rfos.Clock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.*;

public class CdrRotationPolicy extends TimeBasedRotationPolicy {

  private static final Logger LOGGER = LogManager.getLogger(CdrRotationPolicy.class);

  private final int rotationTimeSeconds;

  public CdrRotationPolicy(int rotationTimeSeconds) {
    // Do nothing.
    this.rotationTimeSeconds = rotationTimeSeconds;
  }


  /**
   * @return the instant of the upcoming midnight
   */
  @Override
  public Instant getTriggerInstant(Clock clock) {
//    return clock.now().plusSeconds(this.rotationTimeSeconds);


    Instant instant = clock.now();
    System.out.println(instant);
//    ZonedDateTime utcInstant = instant.atZone(ZoneId.of("UTC"));
//    Instant triggerInstant = LocalDateTime
//        .from(utcInstant)
//        .plusSeconds(this.rotationTimeSeconds)
//        .toInstant(ZoneOffset.UTC);
    Instant triggerInstant = instant.plusSeconds(this.rotationTimeSeconds);
    System.out.println(triggerInstant);
    return triggerInstant;

  }

  @Override
  protected Logger getLogger() {
    return LOGGER;
  }


  @Override
  public String toString() {
    return "CdrRotationPolicy";
  }
}
