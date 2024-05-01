package g3.rm.resourcemanager.processes;

import g3.rm.resourcemanager.jpa_domain.AgentParam;
import g3.rm.resourcemanager.jpa_domain.TaskParam;
import g3.rm.resourcemanager.repositories.AgentParamRepository;
import g3.rm.resourcemanager.repositories.TaskParamRepository;
import g3.rm.resourcemanager.services.HttpResponseService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import g3.rm.resourcemanager.data.TaskObject;
import g3.rm.resourcemanager.router.RouterEventPublisher;
import g3.rm.resourcemanager.services.FileSystemService;
import g3.rm.resourcemanager.services.LoggerService;
import g3.rm.resourcemanager.services.TimerService;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;

public class Collect {
    @Autowired
    private AgentParamRepository agentParamRepository;
    @Autowired
    private TaskParamRepository taskParamRepository;
    @Autowired
    private FileSystemService fileSystemService;
    @Autowired
    private HttpResponseService responseService;
    @Autowired
    private LoggerService loggerService;
    @Autowired
    private TimerService timerService;
    @Autowired
    private RouterEventPublisher eventPublisher;

    private TaskObject taskObject;

    private final Logger LOGGER = LogManager.getLogger("Collect");
    private final String OPERATION = "COLLECT";
    private final String SUCCESS = "COLLECT_DONE";
    private final String ERROR = "COLLECT_ERROR";

    public Collect() {
    }

    public TaskObject getTaskObject() {
        return this.taskObject;
    }

    public void setTaskObject(TaskObject taskObject) {
        this.taskObject = taskObject;
    }

    @Async
    public CompletableFuture<String> start() {
        long taskId = this.taskObject.getTaskId();
        Timer timer = timerService.createCollectTimer(taskId);

        responseService.sendCollectInitResponse(taskObject.getTaskId(), taskObject.getSessionId());
        TaskParam taskParam = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), OPERATION);
        if (taskParam == null) {
            LOGGER.error("Unknown programId: " + this.taskObject.getProgramId());
            timerService.cancelTimer(timer);
            responseService.sendCollectDoneResponse(taskObject.getTaskId(), taskObject.getSessionId(), -1);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        String path = taskParam.getParamValue();
        if (fileSystemService.templateMarkerExists(this.taskObject.getProgramId())) {
            String programHome = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "HOME").getParamValue();
            String scriptName = agentParamRepository.getByName("COLLECT_NAME").getValue();
            path = programHome + File.separator + this.taskObject.getTaskId() + "_scripts" + File.separator + scriptName;
        }
        if(!new File(path).exists()) {
            LOGGER.error("File: " + path + " not found");
            timerService.cancelTimer(timer);
            responseService.sendCollectDoneResponse(taskObject.getTaskId(), taskObject.getSessionId(), -1);
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
        int exitCode;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(args);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputLogPath + File.separator + OPERATION.toLowerCase() + ".log")));
            processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorLogPath + File.separator + OPERATION.toLowerCase() + "_error.log")));
            process = processBuilder.start();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Start process: " + args);

            process.waitFor();
            exitCode = process.exitValue();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Exit value: " + exitCode);

            loggerService.saveLog(this.taskObject.getTaskId(), this.taskObject.getSessionId(), OPERATION);
        } catch (IOException ex) {
            LOGGER.error("Process execution error. Operation: " + OPERATION + ". Start process: " + args + ". Message: " + ex.getMessage(), ex);
            timerService.cancelTimer(timer);
            responseService.sendCollectDoneResponse(taskObject.getTaskId(), taskObject.getSessionId(), -1);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        } catch (InterruptedException ex) {
            LOGGER.error("Process was interrupted. Operation: " + OPERATION + ". Start process: " + args);
            responseService.sendCollectDoneResponse(taskObject.getTaskId(), taskObject.getSessionId(), -1);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        responseService.sendCollectDoneResponse(taskObject.getTaskId(), taskObject.getSessionId(), exitCode);
        if (exitCode != 0) {
            timerService.cancelTimer(timer);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        timerService.cancelTimer(timer);
        eventPublisher.publishTaskEvent(SUCCESS, taskObject);
        return CompletableFuture.completedFuture(SUCCESS);
    }
}