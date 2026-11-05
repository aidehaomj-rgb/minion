package com.minion.core.tools.dev;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

/** JUnit 报告协议夹具，不将模拟 XML 冒充真实 Maven/Gradle 集成。 */
public class ValidationReportsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void nestedSuitesAreNotDoubleCounted() throws Exception {
        Path report = tmp.newFile().toPath();
        SourceVersionTest.write(report, "<testsuites tests='3'><testsuite tests='3'><testsuite tests='2' failures='1'/><testsuite tests='1' skipped='1'/></testsuite></testsuites>");
        ValidationReports.Counts c = ValidationReports.parseJUnit(report);
        assertEquals(3, c.tests); assertEquals(1, c.failures); assertEquals(1, c.skipped);
    }

    @Test public void realFailureNodeOverridesFalseZeroAttribute() throws Exception {
        Path report = tmp.newFile().toPath();
        SourceVersionTest.write(report, "<testsuite tests='1' failures='0'><testcase><failure>assertion</failure></testcase></testsuite>");
        assertEquals(1, ValidationReports.parseJUnit(report).failures);
    }

    @Test public void parentErrorsCannotBeHiddenBySuccessfulChildren() throws Exception {
        Path report = tmp.newFile().toPath();
        SourceVersionTest.write(report, "<testsuites tests='2' errors='1'><testsuite tests='2'/></testsuites>");
        assertEquals(1, ValidationReports.parseJUnit(report).errors);
    }

    @Test public void oldReportsNeverCountAsThisRun() throws Exception {
        Path root = tmp.newFolder().toPath(), report = root.resolve("target/TEST-old.xml");
        SourceVersionTest.write(report, "<testsuite tests='100'/>");
        Map<Path, String> before = ValidationReports.snapshot(root, Arrays.asList(report.getParent()));
        ValidationReports.Evidence old = ValidationReports.collect(root, Arrays.asList(report.getParent()), before);
        assertNull(old.counts); assertEquals(1, old.staleFiles);
        SourceVersionTest.write(report.getParent().resolve("TEST-new.xml"), "<testsuite tests='2' errors='1'/>");
        ValidationReports.Evidence fresh = ValidationReports.collect(root, Arrays.asList(report.getParent(), report), before);
        assertEquals(2, fresh.counts.tests); assertEquals(1, fresh.counts.errors); assertEquals(1, fresh.files.size());
    }

    @Test(expected = IOException.class) public void xxeIsRejected() throws Exception {
        Path report = tmp.newFile().toPath();
        SourceVersionTest.write(report, "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM 'file:///C:/Windows/win.ini'>]><testsuite tests='1'><testcase>&xxe;</testcase></testsuite>");
        ValidationReports.parseJUnit(report);
    }

    @Test(expected = IOException.class) public void impossibleCountersAreRejected() throws Exception {
        Path report = tmp.newFile().toPath();
        SourceVersionTest.write(report, "<testsuite tests='1' failures='2'/>");
        ValidationReports.parseJUnit(report);
    }

    @Test public void zeroUnknownAndAllSkippedAreNotPassed() {
        ValidationReports.Counts counts = new ValidationReports.Counts();
        assertEquals("no_tests", ValidationRunner.resultStatus("test", 0, counts, false));
        assertEquals("command_succeeded", ValidationRunner.resultStatus("test", 0, null, false));
        assertEquals("no_tests", ValidationRunner.resultStatus("test", 5, null, true));
        counts.tests = counts.skipped = 2;
        assertEquals("skipped", ValidationRunner.resultStatus("test", 0, counts, false));
        counts.skipped = 1;
        assertEquals("passed", ValidationRunner.resultStatus("test", 0, counts, false));
        counts.failures = 1;
        assertEquals("failed", ValidationRunner.resultStatus("test", 0, counts, false));
        assertEquals("failed", ValidationRunner.resultStatus("build", 1, null, false));
        assertEquals("command_succeeded", ValidationRunner.resultStatus("command", 0, null, false));
    }
}
