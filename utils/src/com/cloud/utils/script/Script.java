// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.utils.script;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.script.OutputInterpreter.TimedOutLogger;

public class Script implements Callable<String> {
    private static final Logger s_logger = Logger.getLogger(Script.class);

    private final Logger _logger;

    public static final String ERR_EXECUTE = "execute.error";
    public static final String ERR_TIMEOUT = "timeout";
    private int _defaultTimeout = 3600 * 1000; /* 1 hour */
    private volatile boolean _isTimeOut = false;

    private boolean _passwordCommand = false;

    private static final ScheduledExecutorService s_executors = Executors.newScheduledThreadPool(10, new NamedThreadFactory("Script"));

    String _workDir;
    ArrayList<String> _command;
    long _timeout;
    Process _process;
    Thread _thread;

    ScriptBuilder _builder;

    public Script(String command, long timeout, Logger logger) {
        _command = new ArrayList<String>();
        _command.add(command);
        _timeout = timeout;
        if (_timeout == 0) {
        	/* always using default timeout 1 hour to avoid thread hang */
        	_timeout = _defaultTimeout;
        }
        _process = null;
        _logger = logger != null ? logger : s_logger;
    }

    protected Script(ScriptBuilder builder) {
        this(builder._command, builder._timeout, builder._logger);
    }

    public Script(boolean runWithSudo, String command, long timeout, Logger logger) {
        this(command, timeout, logger);
        if (runWithSudo) {
            _command.add(0, "sudo");
        }
    }

    public Script(String command, Logger logger) {
        this(command, 0, logger);
    }

    public Script(String command) {
        this(command, 0, s_logger);
    }

    public Script(String command, long timeout) {
        this(command, timeout, s_logger);
    }

    public void add(String... params) {
        for (String param : params) {
            _command.add(param);
        }
    }

    public void add(String param) {
        _command.add(param);
    }

    public Script set(String name, String value) {
        _command.add(name);
        _command.add(value);
        return this;
    }
    
    public void setWorkDir(String workDir) {
    	_workDir = workDir;
    }

    protected String buildCommandLine(String[] command) {
        StringBuilder builder = new StringBuilder();
        boolean obscureParam = false;
        for (int i = 0; i < command.length; i++) {
            String cmd = command[i];
            if (obscureParam) {
                builder.append("******").append(" ");
                obscureParam = false;
            } else {
                builder.append(command[i]).append(" ");
            }

            if ("-y".equals(cmd) || "-z".equals(cmd)) {
                obscureParam = true;
                _passwordCommand = true;
            }
        }
        return builder.toString();
    }

    protected String buildCommandLine(List<String> command) {
        StringBuilder builder = new StringBuilder();
        boolean obscureParam = false;
        for (String cmd : command) {
            if (obscureParam) {
                builder.append("******").append(" ");
                obscureParam = false;
            } else {
                builder.append(cmd).append(" ");
            }

            if ("-y".equals(cmd) || "-z".equals(cmd)) {
                obscureParam = true;
                _passwordCommand = true;
            }
        }
        return builder.toString();
    }

    public String execute() {
        return execute(new OutputInterpreter.OutputLogger(_logger));
    }

    @Override
    public String toString() {
    	String[] command = _command.toArray(new String[_command.size()]);
    	return buildCommandLine(command);
    }
    
    public String execute(OutputInterpreter interpreter) {
        String[] command = _command.toArray(new String[_command.size()]);

        if (_logger.isDebugEnabled()) {
            _logger.debug("Executing: " + buildCommandLine(command));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if(_workDir != null) 
            	pb.directory(new File(_workDir));
            
            _process = pb.start();
            if (_process == null) {
                _logger.warn("Unable to execute: " + buildCommandLine(command));
                return "Unable to execute the command: " + command[0];
            }

            BufferedReader ir = new BufferedReader(new InputStreamReader(_process.getInputStream()));

            _thread = Thread.currentThread();
            ScheduledFuture<String> future = null;
            if (_timeout > 0) {
                future = s_executors.schedule(this, _timeout, TimeUnit.MILLISECONDS);
            }

            Task task = null;
            if (interpreter != null && interpreter.drain()) {
                task = new Task(interpreter, ir);
                s_executors.execute(task);
            }

            while (true) {
                try {
                    if (_process.waitFor() == 0) {
                        _logger.debug("Execution is successful.");
                        if (interpreter != null) {
                            return interpreter.drain() ? task.getResult() : interpreter.interpret(ir);
                        } else {
                            // null return is ok apparently
                            return (_process.exitValue() == 0) ? "Ok" : "Failed, exit code " + _process.exitValue();
                        }
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    if (!_isTimeOut) {
                        /*
                         * This is not timeout, we are interrupted by others,
                         * continue
                         */
                        _logger.debug("We are interrupted but it's not a timeout, just continue");
                        continue;
                    }

                    TimedOutLogger log = new TimedOutLogger(_process);
                    Task timedoutTask = new Task(log, ir);

                    timedoutTask.run();
                    if (!_passwordCommand) {
                        _logger.warn("Timed out: " + buildCommandLine(command) + ".  Output is: " + timedoutTask.getResult());
                    } else {
                        _logger.warn("Timed out: " + buildCommandLine(command));
                    }

                    return ERR_TIMEOUT;
                } finally {
                    if (future != null) {
                        future.cancel(false);
                    }
                    Thread.interrupted();
                }
            }

            _logger.debug("Exit value is " + _process.exitValue());

            BufferedReader reader = new BufferedReader(new InputStreamReader(_process.getInputStream()), 128);

            String error;
            if (interpreter != null) {
                error = interpreter.processError(reader);
            }
            else {
                error = "Non zero exit code : " + _process.exitValue();
            }
            
            if (_logger.isDebugEnabled()) {
                _logger.debug(error);
            }
            return error;
        } catch (SecurityException ex) {
            _logger.warn("Security Exception....not running as root?", ex);
            StringWriter writer = new StringWriter();
            ex.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        } catch (IOException ex) {
        	// FIXME: Maven removes the X-ecute permission from the deployed scripts.
        	// if the exception is "Permission denied" then, change permissions.
        	
        	if(ex.getMessage().toLowerCase().contains("permission denied") && !command[0].equals("chmod"))
        	{
        		_logger.warn(ex.getMessage());
        		Script script = new Script("chmod", s_logger);
                script.add("0755", command[0]);
                script.execute();
                return execute(interpreter);
        	}
        	
        	_logger.warn("Exception: " + buildCommandLine(command), ex);
            StringWriter writer = new StringWriter();
            ex.printStackTrace(new PrintWriter(writer));
            return writer.toString();
            
        } catch (Exception ex) {
            _logger.warn("Exception: " + buildCommandLine(command), ex);
            StringWriter writer = new StringWriter();
            ex.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        } finally {
            if (_process != null) {
                try {
                    _process.getErrorStream().close();
                } catch (IOException ex) {
                }
                try {
                    _process.getOutputStream().close();
                } catch (IOException ex) {
                }
                try {
                    _process.getInputStream().close();
                } catch (IOException ex) {
                }
                _process.destroy();
            }
        }
    }

    @Override
    public String call() {
        try {
            _logger.trace("Checking exit value of process");
            _process.exitValue();
            _logger.trace("Script ran within the alloted time");
        } catch (IllegalThreadStateException e) {
            _logger.warn("Interrupting script.");
            _isTimeOut = true;
            _thread.interrupt();
        }
        return null;
    }

    public static class Task implements Runnable {
        OutputInterpreter interpreter;
        BufferedReader reader;
        String result;
        boolean done;

        public Task(OutputInterpreter interpreter, BufferedReader reader) {
            this.interpreter = interpreter;
            this.reader = reader;
            this.result = null;
        }

        public void run() {
            done = false;
            try {
                result = interpreter.interpret(reader);
            } catch (IOException ex) {
                StringWriter writer = new StringWriter();
                ex.printStackTrace(new PrintWriter(writer));
                result = writer.toString();
            } catch (Exception ex) {
                StringWriter writer = new StringWriter();
                ex.printStackTrace(new PrintWriter(writer));
                result = writer.toString();
            } finally {
                synchronized (this) {
                    done = true;
                    notifyAll();
                }
                try {
                    reader.close();
                } catch (IOException ex) {
                }
                ;
            }
        }

        public synchronized String getResult() throws InterruptedException {
            if (!done) {
                wait();
            }
            return result;
        }
    }

    public static String findScript(String path, String script) {
        s_logger.debug("Looking for " + script + " in the classpath");

        path = path.replace("/", File.separator);

        URL url = ClassLoader.getSystemResource(script);
        s_logger.debug("System resource: " + url);
        File file = null;
        if (url != null) {
            file = new File(url.getFile());
            s_logger.debug("Absolute path =  " + file.getAbsolutePath());
            return file.getAbsolutePath();
        }

        /**
         * Look in WEB-INF/classes of the webapp
         * URI workaround the URL encoding of url.getFile
         */
        if (path.endsWith(File.separator)) {
        	url = Script.class.getClassLoader().getResource(path + script);
        }
        else {
        	url = Script.class.getClassLoader().getResource(path + File.separator + script);
        }
        s_logger.debug("Classpath resource: " + url);
        if (url != null) {
       	    try {
                file = new File(new URI(url.toString()).getPath());
                s_logger.debug("Absolute path =  " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
            catch (URISyntaxException e) {
                s_logger.warn("Unable to convert " + url.toString() + " to a URI");
            }
        }       

        if (path.endsWith(File.separator)) {
            path = path.substring(0, path.lastIndexOf(File.separator));
        }

        if (path.startsWith(File.separator)) {
            // Path given was absolute so we assume the caller knows what they want.
            file = new File(path + File.separator + script);
            return file.exists() ? file.getAbsolutePath() : null;
        }

        s_logger.debug("Looking for " + script);
        String search = null;
        for (int i = 0; i < 3; i++) {
            if (i == 0) {
                String cp = Script.class.getResource(Script.class.getSimpleName() + ".class").toExternalForm();
                int begin = cp.indexOf(File.separator);

                // work around with the inconsistency of java classpath and file separator on Windows 7
                if (begin < 0)
                    begin = cp.indexOf('/');

                int endBang = cp.lastIndexOf("!");
                int end = cp.lastIndexOf(File.separator, endBang);
                if (end < 0) 
                	end = cp.lastIndexOf('/', endBang);
                if(end < 0)
                	cp = cp.substring(begin);
                else
                	cp = cp.substring(begin, end);

                s_logger.debug("Current binaries reside at " + cp);
                search = cp;
            } else if (i == 1) {
                s_logger.debug("Searching in environment.properties");
                try {
                    final File propsFile = PropertiesUtil.findConfigFile("environment.properties");
                    if (propsFile == null) {
                        s_logger.debug("environment.properties could not be opened");
                    } else {
                        final FileInputStream finputstream = new FileInputStream(propsFile);
                        final Properties props = new Properties();
                        props.load(finputstream);
                        finputstream.close();
                        search = props.getProperty("paths.script");
                    }
                } catch (IOException e) {
                    s_logger.debug("environment.properties could not be opened");
                    continue;
                }
                s_logger.debug("environment.properties says scripts should be in " + search);
            } else {
                s_logger.debug("Searching in the current directory");
                search = ".";
            }

            search += File.separatorChar + path + File.separator;
            do {
                search = search.substring(0, search.lastIndexOf(File.separator));
                file = new File(search + File.separator + script);
                s_logger.debug("Looking for " + script + " in " + file.getAbsolutePath());
            } while (!file.exists() && search.lastIndexOf(File.separator) != -1);

            if (file.exists()) {
                return file.getAbsolutePath();
            }

        }

        file = new File(System.getProperty("paths.script") + File.separator + path + File.separator + script);
        if (file.exists()) {
            return file.getAbsolutePath();
        }

        s_logger.warn("Unable to find script " + script);
        return null;
    }

    public static String runSimpleBashScript(String command) {

        Script s = new Script("/bin/bash");
        s.add("-c");
        s.add(command);

        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        if (s.execute(parser) != null)
            return null;

        String result = parser.getLine();
        if (result == null || result.trim().isEmpty())
            return null;
        else
            return result.trim();
    }
    
    public static String runSimpleBashScript(String command, int timeout) {

        Script s = new Script("/bin/bash", timeout);
        s.add("-c");
        s.add(command);

        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        if (s.execute(parser) != null)
            return null;

        String result = parser.getLine();
        if (result == null || result.trim().isEmpty())
            return null;
        else
            return result.trim();
    }

    public static void main(String[] args) {
        String path = findScript(".", "try.sh");
        Script script = new Script(path, 5000, s_logger);
        script.execute();
        System.exit(1);
    }
}
