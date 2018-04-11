/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.security.audit.logfile;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.RegexFilter;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.logging.Loggers;

import java.util.ArrayList;
import java.util.List;

public class CapturingLogger {

    public static Logger newCapturingLogger(final Level level) throws IllegalAccessException {
        final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        final String name = caller.getClassName() + "." + caller.getMethodName() + "." + level.toString();
        final Logger logger = ESLoggerFactory.getLogger(name);
        Loggers.setLevel(logger, level);
        final MockAppender appender = new MockAppender(name);
        appender.start();
        Loggers.addAppender(logger, appender);
        return logger;
    }

    private static MockAppender getMockAppender(final String name) {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        final LoggerConfig loggerConfig = config.getLoggerConfig(name);
        return (MockAppender) loggerConfig.getAppenders().get(name);
    }

    public static boolean isEmpty(final String name) {
        final MockAppender appender = getMockAppender(name);
        return appender.isEmpty();
    }

    public static List<String> output(final String name, final Level level) {
        final MockAppender appender = getMockAppender(name);
        return appender.output(level);
    }

    private static class MockAppender extends AbstractAppender {

        public final List<String> error = new ArrayList<>();
        public final List<String> warn = new ArrayList<>();
        public final List<String> info = new ArrayList<>();
        public final List<String> debug = new ArrayList<>();
        public final List<String> trace = new ArrayList<>();

        private MockAppender(final String name) throws IllegalAccessException {
            super(name, RegexFilter.createFilter(".*(\n.*)*", new String[0], false, null, null), null);
        }

        @Override
        public void append(LogEvent event) {
            switch (event.getLevel().toString()) {
                // we can not keep a reference to the event here because Log4j is using a thread
                // local instance under the hood
                case "ERROR":
                    error.add(event.getMessage().getFormattedMessage());
                    break;
                case "WARN":
                    warn.add(event.getMessage().getFormattedMessage());
                    break;
                case "INFO":
                    info.add(event.getMessage().getFormattedMessage());
                    break;
                case "DEBUG":
                    debug.add(event.getMessage().getFormattedMessage());
                    break;
                case "TRACE":
                    trace.add(event.getMessage().getFormattedMessage());
                    break;
                default:
                    throw invalidLevelException(event.getLevel());
            }
        }

        private IllegalArgumentException invalidLevelException(Level level) {
            return new IllegalArgumentException("invalid level, expected [ERROR|WARN|INFO|DEBUG|TRACE] but was [" + level + "]");
        }

        public boolean isEmpty() {
            return error.isEmpty() && warn.isEmpty() && info.isEmpty() && debug.isEmpty() && trace.isEmpty();
        }

        public List<String> output(Level level) {
            switch (level.toString()) {
                case "ERROR":
                    return error;
                case "WARN":
                    return warn;
                case "INFO":
                    return info;
                case "DEBUG":
                    return debug;
                case "TRACE":
                    return trace;
                default:
                    throw invalidLevelException(level);
            }
        }
    }

}