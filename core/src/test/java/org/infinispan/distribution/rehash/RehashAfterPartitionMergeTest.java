package org.infinispan.distribution.rehash;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.distribution.BaseDistFunctionalTest;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.jgroups.protocols.DISCARD;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Test(groups = "functional", testName =  "distribution.rehash.RehashAfterPartitionMergeTest", enabled = false, description = "Need to revisit after https://jira.jboss.org/browse/ISPN-493")
public class RehashAfterPartitionMergeTest extends MultipleCacheManagersTest {

   Cache<Object, Object> c1, c2;
   List<Cache<Object, Object>> caches;
   DISCARD d1, d2;

   @Override
   protected void createCacheManagers() throws Throwable {
      caches = createClusteredCaches(2, "test", getDefaultClusteredConfig(Configuration.CacheMode.DIST_SYNC));

      c1 = caches.get(0);
      c2 = caches.get(1);
      d1 = TestingUtil.getDiscardForCache(c1);
      d2 = TestingUtil.getDiscardForCache(c2);
   }

   public void testCachePartition() {
      c1.put("1", "value");
      c2.put("2", "value");

      for (Cache<Object, Object> c: caches) {
         assert "value".equals(c.get("1"));
         assert "value".equals(c.get("2"));
         assert manager(c).getMembers().size() == 2;
      }
      AtomicInteger ai = new AtomicInteger(0);
      manager(c1).addListener(new ViewChangeListener(ai));
      manager(c2).addListener(new ViewChangeListener(ai));

      d1.setDiscardAll(true);
      d2.setDiscardAll(true);

      // Wait till *both* instances have seen the view change.
      while (ai.get() < 2) TestingUtil.sleepThread(500);

      // we should see a network partition
      for (Cache<Object, Object> c: caches) assert manager(c).getMembers().size() == 1;

      c1.put("3", "value");
      c2.put("4", "value");

      assert "value".equals(c1.get("3"));
      assert null == c2.get("3");

      assert "value".equals(c2.get("4"));
      assert null == c1.get("4");

      ai.set(0);

      // lets "heal" the partition
      d1.setDiscardAll(false);
      d2.setDiscardAll(false);

      // wait till we see the view change
      while (ai.get() < 2) TestingUtil.sleepThread(500);

      BaseDistFunctionalTest.RehashWaiter.waitForInitRehashToComplete(c1, c2);

      c1.put("5", "value");
      c2.put("6", "value");
      for (Cache<Object, Object> c: caches) {
         assert "value".equals(c.get("5"));
         assert "value".equals(c.get("6"));
         assert manager(c).getMembers().size() == 2;
      }      
   }

   @Listener
   public static class ViewChangeListener {
      AtomicInteger ai;

      private ViewChangeListener(AtomicInteger ai) {
         this.ai = ai;
      }

      @ViewChanged
      public void handle(ViewChangedEvent e) {
         ai.getAndIncrement();
      }
   }
}