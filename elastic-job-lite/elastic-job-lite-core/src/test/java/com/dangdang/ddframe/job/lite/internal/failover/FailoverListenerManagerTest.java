/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.lite.internal.failover;

import com.dangdang.ddframe.job.config.JobCoreConfiguration;
import com.dangdang.ddframe.job.config.simple.SimpleJobConfiguration;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.fixture.LiteJsonConstants;
import com.dangdang.ddframe.job.lite.fixture.TestSimpleJob;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.lite.internal.listener.AbstractJobListener;
import com.dangdang.ddframe.job.lite.internal.sharding.ExecutionService;
import com.dangdang.ddframe.job.lite.internal.sharding.ShardingService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent.Type;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.unitils.util.ReflectionUtils;

import java.util.Arrays;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class FailoverListenerManagerTest {
    
    @Mock
    private JobNodeStorage jobNodeStorage;
    
    @Mock
    private ConfigurationService configService;
    
    @Mock
    private ExecutionService executionService;
    
    @Mock
    private ShardingService shardingService;
    
    @Mock
    private FailoverService failoverService;
    
    private final FailoverListenerManager failoverListenerManager = new FailoverListenerManager(null, "test_job");
    
    @Before
    public void setUp() throws NoSuchFieldException {
        MockitoAnnotations.initMocks(this);
        ReflectionUtils.setFieldValue(failoverListenerManager, failoverListenerManager.getClass().getSuperclass().getDeclaredField("jobNodeStorage"), jobNodeStorage);
        ReflectionUtils.setFieldValue(failoverListenerManager, "configService", configService);
        ReflectionUtils.setFieldValue(failoverListenerManager, "executionService", executionService);
        ReflectionUtils.setFieldValue(failoverListenerManager, "shardingService", shardingService);
        ReflectionUtils.setFieldValue(failoverListenerManager, "failoverService", failoverService);
    }
    
    @Test
    public void assertStart() {
        failoverListenerManager.start();
        verify(jobNodeStorage, times(3)).addDataListener(ArgumentMatchers.<AbstractJobListener>any());
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsNotRunningItemPath() {
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/other", Type.NODE_ADDED, "");
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsRunningItemPathButNotRemove() {
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/running", Type.NODE_ADDED, "");
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsRunningItemPathAndRemoveButItemCompleted() {
        when(executionService.isCompleted(0)).thenReturn(true);
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/running", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedButDisableFailover() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(
                new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(false).build(), TestSimpleJob.class.getCanonicalName())).build());
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/running", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedAndEnableFailoverButHasRunningItems() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(true).build(), 
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).build());
        when(shardingService.getLocalShardingItems()).thenReturn(Arrays.asList(1, 2));
        when(executionService.hasRunningItems(Arrays.asList(1, 2))).thenReturn(true);
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/running", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService).setCrashedFailoverFlag(0);
        verify(shardingService).getLocalShardingItems();
        verify(executionService).hasRunningItems(Arrays.asList(1, 2));
        verify(failoverService, times(0)).failoverIfNecessary();
    }
    
    @Test
    public void assertJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedAndEnableFailoverAndHasNotRunningItems() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(true).build(), 
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).build());
        when(shardingService.getLocalShardingItems()).thenReturn(Arrays.asList(1, 2));
        when(executionService.hasRunningItems(Arrays.asList(1, 2))).thenReturn(false);
        failoverListenerManager.new JobCrashedJobListener().dataChanged("/test_job/sharding/0/running", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService).setCrashedFailoverFlag(0);
        verify(shardingService).getLocalShardingItems();
        verify(executionService).hasRunningItems(Arrays.asList(1, 2));
        verify(failoverService).failoverIfNecessary();
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsNotRunningItemPath() {
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/other", Type.NODE_ADDED, "");
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsRunningItemPathButNotRemove() {
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/failover", Type.NODE_ADDED, "");
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsRunningItemPathAndRemoveButItemCompleted() {
        when(executionService.isCompleted(0)).thenReturn(true);
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/failover", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedButDisableFailover() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(
                new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(false).build(), TestSimpleJob.class.getCanonicalName())).build());
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/failover", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService, times(0)).setCrashedFailoverFlag(0);
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedAndEnableFailoverButHasRunningItems() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(true).build(), 
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).build());
        when(shardingService.getLocalShardingItems()).thenReturn(Arrays.asList(1, 2));
        when(executionService.hasRunningItems(Arrays.asList(1, 2))).thenReturn(true);
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/failover", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService).setCrashedFailoverFlag(0);
        verify(shardingService).getLocalShardingItems();
        verify(executionService).hasRunningItems(Arrays.asList(1, 2));
        verify(failoverService, times(0)).failoverIfNecessary();
    }
    
    @Test
    public void assertFailoverJobCrashedJobListenerWhenIsRunningItemPathAndRemoveAndItemNotCompletedAndEnableFailoverAndHasNotRunningItems() {
        when(executionService.isCompleted(0)).thenReturn(false);
        when(configService.load(true)).thenReturn(LiteJobConfiguration.newBuilder(new SimpleJobConfiguration(JobCoreConfiguration.newBuilder("test_job", "0/1 * * * * ?", 3).failover(true).build(), 
                TestSimpleJob.class.getCanonicalName())).monitorExecution(true).build());
        when(shardingService.getLocalShardingItems()).thenReturn(Arrays.asList(1, 2));
        when(executionService.hasRunningItems(Arrays.asList(1, 2))).thenReturn(false);
        failoverListenerManager.new FailoverJobCrashedJobListener().dataChanged("/test_job/sharding/0/failover", Type.NODE_REMOVED, "");
        verify(executionService).isCompleted(0);
        verify(failoverService).setCrashedFailoverFlag(0);
        verify(shardingService).getLocalShardingItems();
        verify(executionService).hasRunningItems(Arrays.asList(1, 2));
        verify(failoverService).failoverIfNecessary();
    }
    
    @Test
    public void assertFailoverSettingsChangedJobListenerWhenIsNotFailoverPath() {
        failoverListenerManager.new FailoverSettingsChangedJobListener().dataChanged("/test_job/config/other", Type.NODE_ADDED, LiteJsonConstants.getJobJson());
        verify(failoverService, times(0)).removeFailoverInfo();
    }
    
    @Test
    public void assertFailoverSettingsChangedJobListenerWhenIsFailoverPathButNotUpdate() {
        failoverListenerManager.new FailoverSettingsChangedJobListener().dataChanged("/test_job/config/failover", Type.NODE_ADDED, "");
        verify(failoverService, times(0)).removeFailoverInfo();
    }
    
    @Test
    public void assertFailoverSettingsChangedJobListenerWhenIsFailoverPathAndUpdateButEnableFailover() {
        failoverListenerManager.new FailoverSettingsChangedJobListener().dataChanged("/test_job/config", Type.NODE_UPDATED, LiteJsonConstants.getJobJson());
        verify(failoverService, times(0)).removeFailoverInfo();
    }
    
    @Test
    public void assertFailoverSettingsChangedJobListenerWhenIsFailoverPathAndUpdateButDisableFailover() {
        failoverListenerManager.new FailoverSettingsChangedJobListener().dataChanged("/test_job/config", Type.NODE_UPDATED, LiteJsonConstants.getJobJson(false));
        verify(failoverService).removeFailoverInfo();
    }
}
