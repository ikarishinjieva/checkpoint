package org.tac.nil.checkpoint;

import com.google.common.base.Throwables;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckpointTest {

  @Before
  public void setUp() throws Exception {
    Checkpoint.startServer(getServerIpAndPort());
    Thread.sleep(2000);
    Checkpoint.init(getServerIpAndPort());
  }

  private String getLocalIp() throws UnknownHostException {
    return InetAddress.getLocalHost().getCanonicalHostName();
  }

  @After
  public void tearDown() throws Exception {
    Checkpoint.shutdownServer();
  }

  @Test
  public void aThreadShouldStopAtCheckpoint() throws Exception {
    Checkpoint.add("we_are_at_checkpoint");
    final AtomicBoolean isPassCheckpoint = new AtomicBoolean(false);
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Throwables.propagate(e);
        }
        Checkpoint.kick("we_are_at_checkpoint");
        isPassCheckpoint.set(true);
      }
    };
    thread.start();
    Checkpoint.hold("we_are_at_checkpoint");
    Thread.sleep(1000); //Let other threads go if they could
    Assert.assertFalse(isPassCheckpoint.get());
  }

  private String getServerIpAndPort() throws UnknownHostException {
    return String.format("%s:3333", getLocalIp());
  }

  @Test
  public void twoThreadShouldStopIfNotBothAreKick() throws Exception {
    Checkpoint.add("we_are_at_checkpoint", 2);
    Thread thread0 = new Thread("Thread-0") {
      @Override
      public void run() {
        Checkpoint.kick("we_are_at_checkpoint");
      }
    };
    final AtomicBoolean isCheckpointKickInThread1 = new AtomicBoolean(false);
    Thread thread1 = new Thread("Thread-1") {
      @Override
      public void run() {
        try {
          Thread.sleep(10000);
        } catch (InterruptedException e) {
          Throwables.propagate(e);
        }
        isCheckpointKickInThread1.set(true);
        Checkpoint.kick("we_are_at_checkpoint");
      }
    };

    thread0.start();
    thread1.start();
    Checkpoint.hold("we_are_at_checkpoint");
    Assert.assertTrue(isCheckpointKickInThread1.get());
  }

  @Test
  public void ContShouldWork() throws Exception {
    Checkpoint.add("first_checkpoint");
    Checkpoint.add("second_checkpoint");
    final AtomicBoolean isFirstCheckpointPass = new AtomicBoolean(false);
    final AtomicBoolean isSecondCheckpointPass = new AtomicBoolean(false);

    Thread thread = new Thread() {
      @Override
      public void run() {
        Checkpoint.kick("first_checkpoint");
        isFirstCheckpointPass.set(true);
        Checkpoint.kick("second_checkpoint");
        isSecondCheckpointPass.set(true);
      }
    };
    thread.start();
    Checkpoint.hold("first_checkpoint");
    Assert.assertFalse(isFirstCheckpointPass.get());
    Checkpoint.cont("first_checkpoint");
    Checkpoint.hold("second_checkpoint");
    Assert.assertTrue(isFirstCheckpointPass.get());
    Assert.assertFalse(isSecondCheckpointPass.get());
  }

  @Test
  public void checkpointShouldNotStopIfNotAdded() throws Exception {
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

  @Test
  public void addAndKickAndHoldCheckpointShouldBeRepeatable() throws Exception {
    Checkpoint.add("checkpoint");
    new Thread(new Runnable() {
      @Override
      public void run() {
        Checkpoint.kick("checkpoint");
      }
    }).start();
    Checkpoint.hold("checkpoint");
    try {
      Checkpoint.add("checkpoint");
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }
  }
}
