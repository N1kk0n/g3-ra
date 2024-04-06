package g3.agent.processes;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import g3.agent.data.TaskObject;
import g3.agent.jpa_domain.TaskParam;
import g3.agent.repositories.AgentParamRepository;
import g3.agent.repositories.TaskParamRepository;
import g3.agent.router.RouterEventPublisher;
import g3.agent.services.FileSystemService;
import g3.agent.services.HttpResponseService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class FinalProgressInfo {
    @Autowired
    private AgentParamRepository agentParamRepository;
    @Autowired
    private TaskParamRepository taskParamRepository;
    @Autowired
    private FileSystemService fileSystemService;
    @Autowired
    private HttpResponseService responseService;
    @Autowired
    private RouterEventPublisher eventPublisher;

    private TaskObject taskObject;

    private final Logger LOGGER = LogManager.getLogger("FinalProgressInfo");
    private final String OPERATION = "FINALPROGRESSINFO";
    private final String SUCCESS = "FINALPROGRESSINFO_DONE";
    private final String ERROR = "FINALPROGRESSINFO_ERROR";

    public FinalProgressInfo() {
    }

    public TaskObject getTaskObject() {
        return taskObject;
    }

    public void setTaskObject(TaskObject taskObject) {
        this.taskObject = taskObject;
    }

    @Async
    public CompletableFuture<String> start() {
        TaskParam taskParam = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "PROGRESSINFO");
        if (taskParam == null) {
            LOGGER.error("Unknown programId: " + this.taskObject.getProgramId());
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        String path = taskParam.getParamValue();
        if (fileSystemService.templateMarkerExists(this.taskObject.getProgramId())) {
            String programHome = taskParamRepository.findByProgramIdAndParamName(this.taskObject.getProgramId(), "HOME").getParamValue();
            String scriptName = agentParamRepository.getByName("PROGRESS_INFO_NAME").getValue();
            path = programHome + File.separator + this.taskObject.getTaskId() + "_scripts" + File.separator + scriptName;
        }
        if (!new File(path).exists()) {
            LOGGER.error("File: " + path + " not found");
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }

        List<String> args = new LinkedList<>();
        args.add(path);
        args.add(String.valueOf(this.taskObject.getTaskId()));

        Process process;
        int exitCode;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(args);
            process = processBuilder.start();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Start process: " + args);

            process.waitFor();
            exitCode = process.exitValue();

            JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] pair = line.split(":");
                if (pair.length != 2) {
                    continue;
                }
                String key = pair[0].trim();
                String value = pair[1].trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                //check <value> is digit
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    continue;
                }
                jsonObjectBuilder.add(key, value);
            }
            jsonObjectBuilder.add("taskId", this.taskObject.getTaskId());
            String progressInfo = jsonObjectBuilder.build().toString();

            LOGGER.info("Operation: " + OPERATION + " (Task ID: " + taskObject.getTaskId() + "). Exit value: " + exitCode);
            responseService.sendFinalProgressInfoResponse(progressInfo);
        } catch (IOException ex) {
            LOGGER.error("Process execution error. Operation: " + OPERATION + ". Start process: " + args + ". Message: " + ex.getMessage(), ex);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        } catch (InterruptedException ex) {
            LOGGER.error("Process was interrupted. Operation: " + OPERATION + ". Start process: " + args);
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        if (exitCode != 0) {
            eventPublisher.publishTaskEvent(ERROR, taskObject);
            return CompletableFuture.completedFuture(ERROR);
        }
        eventPublisher.publishTaskEvent(SUCCESS, taskObject);
        return CompletableFuture.completedFuture(SUCCESS);
    }
}
