package g3.rm.resourcemanager.processes;

import g3.rm.resourcemanager.jpa_domain.TaskParam;
import g3.rm.resourcemanager.repositories.AgentParamRepository;
import g3.rm.resourcemanager.repositories.TaskParamRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import g3.rm.resourcemanager.data.TaskObject;
import g3.rm.resourcemanager.jpa_domain.AgentParam;
import g3.rm.resourcemanager.router.RouterEventPublisher;
import g3.rm.resourcemanager.services.FileSystemService;
import g3.rm.resourcemanager.services.HttpResponseService;
import g3.rm.resourcemanager.services.LoggerService;
import g3.rm.resourcemanager.services.ProcessContainerService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Run {
    @Autowired
    private AgentParamRepository agentParamRepository;
    @Autowired
    private TaskParamRepository taskParamRepository;
    @Autowired
    private HttpResponseService responseService;
    @Autowired
    private LoggerService loggerService;
    @Autowired
    private FileSystemService fileSystemService;
    @Autowired
    private ProcessContainerService processContainerService;
    @Autowired
    private RouterEventPublisher eventPublisher;
    

    private TaskObject taskObject;

    private final Logger LOGGER = LogManager.getLogger("Run");
    private final String OPERATION = "RUN";
    private final String SUCCESS = "RUN_DONE";
    private final String ERROR = "RUN_ERROR";
    private final String STOP = "RUN_STOP";

    public Run() {
    }

    public TaskObject getTaskObject() {
        return taskObject;
    }

    public void setTaskObject(TaskObject taskObject) {
        this.taskObject = taskObject;
    }

    @Async
    public CompletableFuture<String> start() {
        responseService.sendRunInitResponse(taskObject.getTaskId(), taskObject.getSessionId());
        TaskParam taskParam = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), OPERATION);
        if (taskParam == null) {
            LOGGER.error("Unknown programId: " + this.taskObject.getProgramId());
            responseService.sendRunDoneResponse(taskObject.getTaskId(),
                                                taskObject.getSessionId(),
                                                taskObject.getDeviceNameList(), -1, -1, "");
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        String path = taskParam.getParamValue();
        if (fileSystemService.templateMarkerExists(this.taskObject.getProgramId())) {
            String programHome = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "HOME").getParamValue();
            String scriptName = agentParamRepository.getByName("RUN_NAME").getValue();
            path = programHome + File.separator + this.taskObject.getTaskId() + "_scripts" + File.separator + scriptName;
        }
        if (!new File(path).exists()) {
            LOGGER.error("File: " + path + " not found");
            responseService.sendRunDoneResponse(taskObject.getTaskId(),
                                                taskObject.getSessionId(),
                                                taskObject.getDeviceNameList(), -1, -1, "");
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }

        AgentParam agentParam = agentParamRepository.getByName("TASK_LOG_DIR");
        String logDir = agentParam.getValue();
        String outputLogPath = logDir + File.separator + taskObject.getTaskId() + File.separator + taskObject.getSessionId();
        String errorLogPath = logDir + File.separator + taskObject.getTaskId() + File.separator + taskObject.getSessionId();
        File outputLog = new File(outputLogPath);
        if (!outputLog.exists()) {
            outputLog.mkdirs();
        }
        File errorLog = new File(errorLogPath);
        if (!errorLog.exists()) {
            errorLog.mkdirs();
        }

        List<String> args = new LinkedList<>();
        args.add(path);
        args.add(String.valueOf(this.taskObject.getTaskId()));

        Process process;
        int exitCode = -1;
        int programCode = 1;
        String boomerangCode = "";
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(args);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputLogPath + File.separator + OPERATION.toLowerCase() + ".log")));
            processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorLogPath + File.separator + OPERATION.toLowerCase() + "_error.log")));
            process = processBuilder.start();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Start process: " + args);

            process.waitFor();
            exitCode = process.exitValue();

            boomerangCode = getBoomerangCode();
            programCode = getProgramCode();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Exit value: " + exitCode);

            loggerService.saveLog(this.taskObject.getTaskId(), this.taskObject.getSessionId(), OPERATION);
            
            if (fileSystemService.stopFlagExists(this.taskObject.getTaskId(), this.taskObject.getSessionId(), this.taskObject.getProgramId()))
                throw new InterruptedException("Process was interrupted. Operation: " + OPERATION + ". Start process: " + args);
        
        } catch (IOException ex) {
            LOGGER.error("Process execution error. Operation: " + OPERATION + ". Start process: " + args + ". Message: " + ex.getMessage(), ex);
            responseService.sendRunDoneResponse(taskObject.getTaskId(),
                                                taskObject.getSessionId(),
                                                taskObject.getDeviceNameList(), exitCode, programCode, boomerangCode);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        } catch (InterruptedException ex) {
            LOGGER.info(ex.getMessage());
            waitForStopCompletion(this.taskObject.getTaskId());
            
            responseService.sendRunDoneResponse(taskObject.getTaskId(),
                                                taskObject.getSessionId(),
                                                taskObject.getDeviceNameList(), exitCode, programCode, boomerangCode);
            eventPublisher.publishTaskEvent(STOP, taskObject);
            return CompletableFuture.completedFuture(STOP);
        }
        responseService.sendRunDoneResponse(taskObject.getTaskId(),
                                            taskObject.getSessionId(),
                                            taskObject.getDeviceNameList(), exitCode, programCode, boomerangCode);
        if (exitCode != 0) {
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        eventPublisher.publishTaskEvent(SUCCESS, taskObject);
        return CompletableFuture.completedFuture(SUCCESS);
    }

    private int getProgramCode() {
        int programCode = 1;
        try {
            TaskParam taskParam = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "HOME");
            String homeDir = taskParam.getParamValue();
            String programCodePath = homeDir + File.separator + this.taskObject.getTaskId() + "/" + this.taskObject.getTaskId() + ".kod";
            File taskCodeFile = new File(programCodePath);
            if (!taskCodeFile.exists()) {
                return programCode;
            }
            FileReader fileReader = new FileReader(taskCodeFile);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String programCodeStr = bufferedReader.readLine();
            bufferedReader.close();
            fileReader.close();
            if (programCodeStr.contains("=")) {
                programCodeStr = programCodeStr.split("=")[1];
            }
            programCode = Integer.parseInt(programCodeStr);
        } catch (IOException ex) {
            LOGGER.error("Error while get program code: " + ex.getMessage(), ex);
        }
        return programCode;
    }

    private String getBoomerangCode() {
        String boomerangCode = "";
        try {
            TaskParam taskParam = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "HOME");
            String homeDir = taskParam.getParamValue();
            String taskCodePath = homeDir + File.separator + this.taskObject.getTaskId() + "/TaskCode";
            File taskCodeFile = new File(taskCodePath);
            if (!taskCodeFile.exists()) {
                return boomerangCode;
            }
            FileReader fileReader = new FileReader(taskCodeFile);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            boomerangCode = bufferedReader.readLine();
            bufferedReader.close();
            fileReader.close();
        } catch (IOException ex) {
            LOGGER.error("Error while get boomerang code: " + ex.getMessage(), ex);
        }
        return boomerangCode;
    }

    private void waitForStopCompletion(long taskId) {
        try {
            LOGGER.info("Waiting for STOP complete...");
            while (true) {
                if (processContainerService.checkExist("STOP", taskId)) {
                    Thread.sleep(10000);
                } else {
                    return;
                }    
            }
        } catch (Exception e) {
            return;
        }
    }
}