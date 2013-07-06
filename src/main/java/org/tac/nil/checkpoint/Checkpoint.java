package org.tac.nil.checkpoint;

import com.google.common.base.Throwables;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.*;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Checkpoint {
  public static final int SESSION_TIMEOUT = 60000;
  private static Map<String, Integer> masterMutex = new ConcurrentHashMap<String, Integer>();
  private static Map<String, Integer> slaveMutex = new ConcurrentHashMap<String, Integer>();
  private static ZooKeeper zooKeeper = null;
  private static Logger logger = LoggerFactory.getLogger(Checkpoint.class);
  private static Watcher watcher = new Watcher() {
    @Override
    public void process(WatchedEvent watchedEvent) {
      if (!Event.EventType.NodeChildrenChanged.equals(watchedEvent.getType())) {
        return;
      }
      String path = watchedEvent.getPath();
      logger.debug(String.format("Process ZooKeeper watcher event : %s", path));
      String checkpointName = getCheckpointNameFromPath(watchedEvent.getPath());
      Integer mutex = masterMutex.get(checkpointName);
      assert null != mutex;
      logger.debug(String.format("Notify master mutex : %s", checkpointName));
      synchronized (mutex) {
        mutex.notifyAll();
      }
    }

    public String getCheckpointNameFromPath(String path) {
      String[] splits = path.split("/");
      if (3 != splits.length) {
        throw new IllegalArgumentException(String.format("Could not get checkpoint name from path %s", path));
      }
      String checkpointName = splits[2];
      return checkpointName;
    }
  };
  private static ServerCnxnFactory serverFactory;

  public static void shutdownServer() {
    serverFactory.shutdown();
    zooKeeper = null;
    masterMutex.clear();
    slaveMutex.clear();
  }

  public static void startServer(String ip, int port) {
    String dataDirectory = System.getProperty("java.io.tmpdir");
    File dir = new File(dataDirectory, "zookeeper").getAbsoluteFile();
    try {
      FileUtils.deleteDirectory(dir);
    } catch (IOException e) {
      Throwables.propagate(e);
    }
    try {
      ZooKeeperServer server = new ZooKeeperServer(dir, dir, 2000);
      serverFactory = NIOServerCnxnFactory.createFactory(new InetSocketAddress(ip, port), 1000);
      serverFactory.startup(server);
    } catch (IOException e) {
      Throwables.propagate(e);
    } catch (InterruptedException e) {
      Throwables.propagate(e);
    }
  }

  public static synchronized void init(String address) {
    logger.debug(String.format("init checkpoint address = %s", address));
    try {
      zooKeeper = new ZooKeeper(address, SESSION_TIMEOUT, watcher);
    } catch (IOException e) {
      Throwables.propagate(e);
    }
    createRootPathIfNeed();
  }

  private static void createRootPathIfNeed() {
    if (!hasPath("/checkpoint")) {
      createPath("/checkpoint");
    }
  }

  private static boolean hasPath(String path) {
    try {
      return null != zooKeeper.exists(path, false);
    } catch (KeeperException e) {
      Throwables.propagate(e);
    } catch (InterruptedException e) {
      Throwables.propagate(e);
    }
    return false;
  }

  private static void createPath(String path) {
    try {
      zooKeeper.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    } catch (KeeperException e) {
      Throwables.propagate(e);
    } catch (InterruptedException e) {
      Throwables.propagate(e);
    }
  }

  private static void checkInit() {
    if (null == zooKeeper) {
      throw new IllegalStateException("Checkpoint is not init");
    }
  }

  public static void add(String checkpointName, int expectedKickCount) {
    logger.debug(String.format("Add checkpoint : %s", checkpointName));
    checkInit();
    Checkpoint.masterMutex.put(checkpointName, new Integer(expectedKickCount));
    Checkpoint.slaveMutex.put(checkpointName, new Integer(1));
    createPath(String.format("/checkpoint/%s", checkpointName));
  }

  public static void add(String checkpointName) {
    add(checkpointName, 1);
  }

  public static void kick(String checkpointName) {
    logger.debug(String.format("Kick checkpoint : %s", checkpointName));
    if (null == zooKeeper) {
      logger.debug("No zookeeper found, ignore kick");
      return;
    }
    checkInit();
    if (!hasPath(String.format("/checkpoint/%s", checkpointName))) {
      logger.debug(String.format("No checkpoint %s found, ignore kick", checkpointName));
      return;
    }
    createPath(String.format("/checkpoint/%s/%s", checkpointName, getCheckpointId()));
    Integer mutex = slaveMutex.get(checkpointName);
    synchronized (mutex) {
      try {
        logger.debug(String.format("Waiting slave mutex : %s", checkpointName));
        mutex.wait();
      } catch (InterruptedException e) {
        Throwables.propagate(e);
      }
    }
  }

  private static AtomicInteger kickSeed = new AtomicInteger(1);

  private static final long seed = System.nanoTime();

  private static String getCheckpointId() {
    try {
      return String.format("%s-%s-%s",
              InetAddress.getLocalHost().getCanonicalHostName(),
              seed,
              kickSeed.addAndGet(1));
    } catch (UnknownHostException e) {
      Throwables.propagate(e);
    }
    return null;
  }

  public static void hold(String checkpointName) {
    logger.debug(String.format("Hold checkpoint : %s", checkpointName));
    checkInit();
    assert masterMutex.containsKey(checkpointName);
    Integer mutex = masterMutex.get(checkpointName);
    while (true) {
      synchronized (mutex) {
        int childrenSize = getPathChildrenSize(String.format("/checkpoint/%s", checkpointName));
        if (childrenSize < mutex) {
          try {
            logger.debug(String.format("Waiting master mutex : %s", checkpointName));
            mutex.wait();
          } catch (InterruptedException e) {
            Throwables.propagate(e);
          }
        } else {
          break;
        }
      }
    }
  }

  private static int getPathChildrenSize(String path) {
    int childrenSize = 0;
    try {
      childrenSize = zooKeeper.getChildren(path, true).size();
    } catch (KeeperException e) {
      Throwables.propagate(e);
    } catch (InterruptedException e) {
      Throwables.propagate(e);
    }
    return childrenSize;
  }

  public static void cont(String checkpointName) {
    logger.debug(String.format("Cont checkpoint %s", checkpointName));
    Integer mutex = slaveMutex.get(checkpointName);
    assert null != mutex;
    synchronized (mutex) {
      mutex.notifyAll();
    }
  }
}
