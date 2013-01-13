package redis.netty.client;

import org.junit.Test;
import redis.Command;
import redis.netty.BulkReply;
import redis.netty.StatusReply;
import spullara.util.concurrent.Promise;
import spullara.util.functions.Block;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test the base redis client. Default redis required.
 */
public class RedisClientBaseTest {
  @Test
  public void testConnect() throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicBoolean success = new AtomicBoolean();
    final AtomicReference<RedisClientBase> client = new AtomicReference<>();
    Promise<RedisClientBase> connect = RedisClientBase.connect("localhost", 6379);
    connect.onSuccess(new Block<RedisClientBase>() {
      @Override
      public void apply(RedisClientBase redisClientBase) {
        success.set(true);
        client.set(redisClientBase);
        done.countDown();
      }
    }).onFailure(new Block<Throwable>() {
      @Override
      public void apply(Throwable throwable) {
        success.set(false);
        done.countDown();
      }
    });
    done.await(5000, TimeUnit.MILLISECONDS);
    final CountDownLatch done2 = new CountDownLatch(1);
    assertTrue(success.get());
    client.get().close().onSuccess(new Block<Void>() {
      @Override
      public void apply(Void aVoid) {
        success.set(true);
        done2.countDown();
      }
    }).onFailure(new Block<Throwable>() {
      @Override
      public void apply(Throwable throwable) {
        success.set(false);
        done2.countDown();
      }
    });
    done2.await(5000, TimeUnit.MILLISECONDS);
    assertTrue(success.get());
  }

  @Test
  public void testConnectFailure() throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicBoolean success = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    Promise<RedisClientBase> connect = RedisClientBase.connect("localhost", 6380);
    connect.onSuccess(new Block<RedisClientBase>() {
      @Override
      public void apply(RedisClientBase redisClientBase) {
        success.set(true);
        done.countDown();
      }
    }).onFailure(new Block<Throwable>() {
      @Override
      public void apply(Throwable throwable) {
        success.set(false);
        failure.set(throwable);
        done.countDown();
      }
    });
    done.await(5000, TimeUnit.MILLISECONDS);
    assertFalse(success.get());
    assertEquals("Connection refused", failure.get().getMessage());
  }

  @Test
  public void testExecute() throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicBoolean success = new AtomicBoolean();
    RedisClientBase.connect("localhost", 6379).onSuccess(new Block<RedisClientBase>() {
      @Override
      public void apply(final RedisClientBase redisClientBase) {
        redisClientBase.execute(StatusReply.class, new Command("set", "test", "test")).onSuccess(new Block<StatusReply>() {
          @Override
          public void apply(StatusReply reply) {
            if (reply.data().equals("OK")) {
              redisClientBase.execute(BulkReply.class, new Command("get", "test")).onSuccess(new Block<BulkReply>() {
                @Override
                public void apply(BulkReply reply) {
                  if (reply.asAsciiString().equals("test")) {
                    success.set(true);
                  }
                  done.countDown();
                  redisClientBase.close();
                }
              });
            } else {
              done.countDown();
              redisClientBase.close();
            }
          }
        });
      }
    });
    done.await(5000, TimeUnit.MILLISECONDS);
    assertTrue(success.get());
  }

  @Test
  public void testCommands() throws InterruptedException {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<StatusReply> setOK = new AtomicReference<>();
    final AtomicReference<BulkReply> getTest2 = new AtomicReference<>();
    RedisClient.connect("localhost", 6379).onSuccess(new Block<RedisClient>() {
      @Override
      public void apply(final RedisClient redisClient) {
        redisClient.set("test", "test2").onSuccess(new Block<StatusReply>() {
          @Override
          public void apply(StatusReply statusReply) {
            setOK.set(statusReply);
            redisClient.get("test").onSuccess(new Block<BulkReply>() {
              @Override
              public void apply(BulkReply bulkReply) {
                getTest2.set(bulkReply);
                redisClient.close().onSuccess(new Block<Void>() {
                  @Override
                  public void apply(Void aVoid) {
                    done.countDown();
                  }
                });
              }
            });
          }
        });
      }
    });
    done.await(5000, TimeUnit.MILLISECONDS);
    assertEquals("OK", setOK.get().data());
    assertEquals("test2", getTest2.get().asAsciiString());
  }

  @Test
  public void testSerialPerformance() throws InterruptedException {
    final CountDownLatch done = new CountDownLatch(1);
    final int[] i = new int[1];
    RedisClient.connect("localhost", 6379).onSuccess(new Block<RedisClient>() {
      long start;
      private Block<StatusReply> setBlock;

      void again(RedisClient redisClient) {
        apply(redisClient);
      }

      @Override
      public void apply(final RedisClient redisClient) {
        if (start == 0) {
          setBlock = new Block<StatusReply>() {
            @Override
            public void apply(StatusReply statusReply) {
              again(redisClient);
            }
          };
          start = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - start < 5000) {
          redisClient.set(String.valueOf(i[0]++), "test2").onSuccess(setBlock);
        } else {
          redisClient.close().onSuccess(new Block<Void>() {
            @Override
            public void apply(Void aVoid) {
              done.countDown();
            }
          });
        }
      }
    });
    done.await(6000, TimeUnit.MILLISECONDS);
    System.out.println("Completed " + i[0] / 5 + " per second");
  }

  @Test
  public void testPipelinePerformance() throws InterruptedException {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicInteger total = new AtomicInteger();
    RedisClient.connect("localhost", 6379).onSuccess(new Block<RedisClient>() {

      @Override
      public void apply(final RedisClient redisClient) {
          new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                final Semaphore semaphore = new Semaphore(100);
                Runnable release = new Runnable() {
                  @Override
                  public void run() {
                    semaphore.release();
                  }
                };
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 5000) {
                  semaphore.acquire();
                  redisClient.set(String.valueOf(total.getAndIncrement()), "test2").ensure(release);
                }
                semaphore.acquire(100);
                done.countDown();
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }
          }).start();
      }
    });
    done.await(6000, TimeUnit.MILLISECONDS);
    if (total.get() == 100) {
      fail("Failed to complete any requests");
    }
    System.out.println("Completed " + total.get() / 5 + " per second");
  }

}