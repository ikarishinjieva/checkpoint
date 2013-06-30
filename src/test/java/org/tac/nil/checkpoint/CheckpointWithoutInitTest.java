package org.tac.nil.checkpoint;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class CheckpointWithoutInitTest {
  @Test
  public void checkpointWithoutInitShouldWork() throws Exception {
    final AtomicBoolean ifCheckpointPass = new AtomicBoolean(false);
    Thread thread = new Thread() {
      @Override
      public void run() {
        Checkpoint.kick("first_checkpoint");
        ifCheckpointPass.set(true);
      }
    };
    thread.start();
    Thread.sleep(1000);
    Assert.assertTrue(ifCheckpointPass.get());
  }
}
