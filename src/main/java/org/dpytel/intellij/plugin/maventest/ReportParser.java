/*
 * Copyright 2014 Dawid Pytel
 *
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
 */

package org.dpytel.intellij.plugin.maventest;

import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor;
import com.intellij.execution.testframework.sm.runner.events.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import jetbrains.buildServer.messages.serviceMessages.TestIgnored;
import jetbrains.buildServer.messages.serviceMessages.TestStarted;
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

public class ReportParser {
    private static final Logger logger = Logger.getInstance(ReportParser.class);

    private final GeneralTestEventsProcessor testEventsProcessor;

    public ReportParser(GeneralTestEventsProcessor testEventsProcessor) {
        this.testEventsProcessor = testEventsProcessor;
    }

    public void parseTestSuite(VirtualFile child) throws IOException, JDOMException {
        SAXBuilder builder = new SAXBuilder();
        try (FileInputStream in = new FileInputStream(child.getCanonicalPath())) {
            Document document = builder.build(in);
            Element rootElement = document.getRootElement();
            Attribute nameAttribute = rootElement.getAttribute("name");
            String name = nameAttribute.getValue();
            TestSuiteStarted suiteStarted = new TestSuiteStarted(name);
            testEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent(suiteStarted, null));
            List<Element> testcases = rootElement.getChildren("testcase");

            for (Element testcase : testcases) {
                parseTestCase(testcase);
            }

            testEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent(name));
        }
    }

    private void parseTestCase(Element testcase) {
        String methodName = testcase.getAttributeValue("name");

        testEventsProcessor.onTestStarted(new TestStartedEvent(new TestStarted(methodName, true, null), null));

        Long duration = getDuration(testcase);
        if (!testcase.getChildren().isEmpty()) {
            StringBuilder sysOut = new StringBuilder();
            StringBuilder sysErr = new StringBuilder();

            Runnable report = null;

            for (Element child : testcase.getChildren()) {
                String message = child.getAttributeValue("message", "");
                switch (child.getName()) {
                    case "skipped":
                        report = () -> testEventsProcessor.onTestIgnored(new TestIgnoredEvent(new TestIgnored(methodName, message), null));
                        continue;
                    case "failure":
                        report = () -> reportTestFailure(methodName, false, message, child.getText());
                        continue;
                    case "error":
                        report = () -> reportTestFailure(methodName, true, message, child.getText());
                        continue;
                    case "system-out":
                        sysOut.append(child.getText());
                        continue;
                    case "system-err":
                        sysErr.append(child.getText());
                        continue;
                    default:
                        logger.warn("unknown testcase property '" + child.getName() + "'");
                }
            }

            if (sysOut.length() > 0) {
                testEventsProcessor.onTestOutput(new TestOutputEvent(methodName, sysOut.toString(), true));
            }
            if (sysErr.length() > 0) {
                testEventsProcessor.onTestOutput(new TestOutputEvent(methodName, sysErr.toString(), false));
            }

            if (null != report) {
                report.run();
            }
        }
        reportTestFinished(methodName, duration);
    }

    private void reportTestFinished(String methodName, Long duration) {
        testEventsProcessor.onTestFinished(new TestFinishedEvent(methodName, duration));
    }

    private void reportTestFailure(String methodName, boolean testError, String failureMessage, String stackTrace) {
        testEventsProcessor.onTestFailure(new TestFailedEvent(methodName, failureMessage, stackTrace, testError, null, null));
    }

    private Long getDuration(Element testcase) {
        String timeValue = testcase.getAttributeValue("time", (String) null);
        if (StringUtil.isEmpty(timeValue)) {
            return null;
        }
        return (long)(Double.parseDouble(timeValue) * 1000);
    }
}