/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.plugin.trigger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.inlong.agent.conf.JobProfile;
import org.apache.inlong.agent.conf.TriggerProfile;
import org.apache.inlong.agent.constant.AgentConstants;
import org.apache.inlong.agent.constant.JobConstants;
import org.apache.inlong.agent.plugin.AgentBaseTestsHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;

public class TestWatchDirTrigger {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestWatchDirTrigger.class);
    private static Path testRootDir;
    private static DirectoryTrigger trigger;
    private static AgentBaseTestsHelper helper;

    @ClassRule
    public static final TemporaryFolder watchFolder = new TemporaryFolder();

    public Set<String> pathPatternCache = new HashSet<>();

    @BeforeClass
    public static void setup() throws Exception {
        helper = new AgentBaseTestsHelper(TestWatchDirTrigger.class.getName()).setupAgentHome();
        testRootDir = helper.getTestRootDir();
        LOGGER.info("test root dir is {}", testRootDir);
        trigger = new DirectoryTrigger();
        TriggerProfile jobConf = TriggerProfile.parseJsonStr("");
        jobConf.setInt(AgentConstants.TRIGGER_CHECK_INTERVAL, 1);
        jobConf.set(JobConstants.JOB_ID, "1");
        trigger.init(jobConf);
        trigger.start();
    }

    @AfterClass
    public static void teardown() throws Exception {
        LOGGER.info("start to teardown test case");
        trigger.stop();
        trigger.join();
        helper.teardownAgentHome();
    }

    @After
    public void teardownEach() {
        pathPatternCache.forEach(trigger::unregister);
        for (File file : watchFolder.getRoot().listFiles()) {
            FileUtils.deleteQuietly(file);
        }
        trigger.getFetchedJob().clear();
    }

    public void registerPathPattern(Set<String> whiteList, Set<String> blackList, String offset) throws IOException {
        pathPatternCache.addAll(trigger.register(whiteList, offset, blackList));
    }

    @Test
    public void testWatchEntity() throws Exception {
        PathPattern a1 = new PathPattern("1", Collections.singleton(helper.getParentPath().toString()), Sets.newHashSet());
        PathPattern a2 = new PathPattern("1", Collections.singleton(helper.getParentPath().toString()), Sets.newHashSet());
        HashMap<PathPattern, Integer> map = new HashMap<>();
        map.put(a1, 10);
        Integer result = map.remove(a2);
        Assert.assertEquals(a1, a2);
        Assert.assertEquals(10, result.intValue());
    }

    @Test
    public void testBlackList() throws Exception {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return;
        }

        registerPathPattern(
                Sets.newHashSet(watchFolder.getRoot().getAbsolutePath() + File.separator + "**" + File.separator + "*.log"),
                Sets.newHashSet(watchFolder.getRoot().getAbsolutePath() + File.separator + "tmp"),
                null);
        File file1 = watchFolder.newFile("1.log");
        File tmp = watchFolder.newFolder("tmp");
        File file2 = new File(tmp.getAbsolutePath() + File.separator + "2.log");
        file2.createNewFile();
        await().atMost(10, TimeUnit.SECONDS).until(()  -> trigger.getFetchedJob().size() == 1);
        Collection<JobProfile> jobs = trigger.getFetchedJob();
        Set<String> jobPaths = jobs.stream()
                .map(job -> job.get(JobConstants.JOB_DIR_FILTER_PATTERNS, null))
                .collect(Collectors.toSet());
        Assert.assertTrue(jobPaths.contains(file1.getAbsolutePath()));
    }

    @Test
    public void testCreateBeforeWatch() throws Exception {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return;
        }

        File tmp = watchFolder.newFolder("tmp");
        File file1 = new File(tmp.getAbsolutePath() + File.separator + "1.log");
        file1.createNewFile();
        registerPathPattern(
                Sets.newHashSet(
                        watchFolder.getRoot().getAbsolutePath() + File.separator + "**" + File.separator + "*.log"),
                Collections.emptySet(),null);
        await().atMost(10, TimeUnit.SECONDS).until(()  -> trigger.getFetchedJob().size() == 1);
    }

    @Test
    public void testWatchDeepMatch() throws Exception {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return;
        }

        registerPathPattern(
                Sets.newHashSet(
                        watchFolder.getRoot().getAbsolutePath() + File.separator + "**" + File.separator + "*.log"),
                Collections.emptySet(),null);
        File tmp = watchFolder.newFolder("tmp", "deep");
        File file4 = new File(tmp.getAbsolutePath() + File.separator + "1.log");
        file4.createNewFile();
        await().atMost(10, TimeUnit.SECONDS).until(()  -> trigger.getFetchedJob().size() == 1);
    }

    @Test
    public void testMultiPattern() throws Exception {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return;
        }

        registerPathPattern(
                Sets.newHashSet(
                        watchFolder.getRoot().getAbsolutePath() + File.separator + "tmp" + File.separator + "*.log",
                        watchFolder.getRoot().getAbsolutePath() + File.separator + "**" + File.separator + "*.txt"),
                Collections.emptySet(),null);
        File file1 = watchFolder.newFile("1.txt");
        File file2 = watchFolder.newFile("2.log");
        File file3 = watchFolder.newFile("3.tar.gz");
        File tmp = watchFolder.newFolder("tmp");
        File file4 = new File(tmp.getAbsolutePath() + File.separator + "4.txt");
        file4.createNewFile();
        File file5 = new File(tmp.getAbsolutePath() + File.separator + "5.log");
        file5.createNewFile();

        await().atMost(10, TimeUnit.SECONDS).until(()  -> trigger.getFetchedJob().size() == 3);
        Collection<JobProfile> jobs = trigger.getFetchedJob();
        Set<String> jobPaths = jobs.stream()
                .map(job -> job.get(JobConstants.JOB_DIR_FILTER_PATTERNS, null))
                .collect(Collectors.toSet());
        Assert.assertTrue(jobPaths.contains(file1.getAbsolutePath()));
        Assert.assertTrue(jobPaths.contains(file4.getAbsolutePath()));
        Assert.assertTrue(jobPaths.contains(file5.getAbsolutePath()));
    }
}
