package cn.localops.jda.ui;

import cn.localops.jda.config.AppPaths;
import cn.localops.jda.config.DefaultJenkinsSettings;
import cn.localops.jda.config.EnvConfig;
import cn.localops.jda.config.EnvConfigService;
import cn.localops.jda.config.LegacyCredentialImporter;
import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.DeploymentOutcome;
import cn.localops.jda.model.DeploymentProgress;
import cn.localops.jda.model.DeploymentRunResult;
import cn.localops.jda.model.FrontendDeploymentMode;
import cn.localops.jda.model.HostSnapshot;
import cn.localops.jda.model.JobDraft;
import cn.localops.jda.model.JobPreparationPlan;
import cn.localops.jda.model.ManagedRuntime;
import cn.localops.jda.model.NodeStatus;
import cn.localops.jda.model.ProjectProfile;
import cn.localops.jda.model.ProductionRuntimeConfig;
import cn.localops.jda.model.StorageVolume;
import cn.localops.jda.model.TriggerSettings;
import cn.localops.jda.service.AgentProvisioningService;
import cn.localops.jda.service.BackendServiceNaming;
import cn.localops.jda.service.DiagnosticsService;
import cn.localops.jda.service.GerritClient;
import cn.localops.jda.service.JenkinsClient;
import cn.localops.jda.service.JenkinsException;
import cn.localops.jda.service.JobProvisioningService;
import cn.localops.jda.service.LocalRuntimeDashboardService;
import cn.localops.jda.service.PortAvailabilityService;
import cn.localops.jda.service.ProjectDetectionService;
import cn.localops.jda.service.ProductionDirectoryService;
import cn.localops.jda.service.ProductionConfigService;
import cn.localops.jda.service.RecentProjectService;
import cn.localops.jda.service.RedisConfigDetector;
import cn.localops.jda.service.SystemProbeService;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainController {
    private final EnvConfigService configService = new EnvConfigService(AppPaths.configFile());
    private final SystemProbeService systemProbeService = new SystemProbeService();
    private final ProjectDetectionService projectDetectionService = new ProjectDetectionService();
    private final PortAvailabilityService portAvailabilityService = new PortAvailabilityService();
    private final LocalRuntimeDashboardService localRuntimeDashboardService = new LocalRuntimeDashboardService();
    private final ProductionDirectoryService productionDirectoryService = new ProductionDirectoryService();
    private final ProductionConfigService productionConfigService = new ProductionConfigService();
    private final RedisConfigDetector redisConfigDetector = new RedisConfigDetector();
    private final RecentProjectService recentProjectService = new RecentProjectService(configService);
    private final AgentProvisioningService agentProvisioningService = new AgentProvisioningService(configService);
    private final DiagnosticsService diagnosticsService = new DiagnosticsService();
    private final GerritClient gerritClient = new GerritClient();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PauseTransition gerritLookupDelay = new PauseTransition(Duration.millis(650));
    private final WizardState wizardState = new WizardState();

    @FXML private VBox onboardingPane;
    @FXML private VBox projectPane;
    @FXML private VBox deploymentPane;
    @FXML private VBox dashboardPane;
    @FXML private ScrollPane contentScrollPane;
    @FXML private Button navDashboardButton;
    @FXML private Button navStepOneButton;
    @FXML private Button navStepTwoButton;
    @FXML private Button navStepThreeButton;
    @FXML private Label headerStatus;
    @FXML private Label hostNameLabel;
    @FXML private Label osLabel;
    @FXML private Label architectureLabel;
    @FXML private FlowPane diskListPane;
    @FXML private ComboBox<StorageVolume> installDriveComboBox;
    @FXML private Label installPathLabel;
    @FXML private Label connectionLabel;
    @FXML private Label agentNameLabel;
    @FXML private Label currentStepTitle;
    @FXML private Label currentStepDetail;
    @FXML private Label footerStatus;
    @FXML private ProgressBar setupProgress;
    @FXML private Button provisionButton;
    @FXML private Button retryButton;
    @FXML private TextField projectPathField;
    @FXML private Label frontendResultLabel;
    @FXML private Label backendResultLabel;
    @FXML private Label deploymentProjectSummaryLabel;
    @FXML private TextField gerritProjectField;
    @FXML private TextField repositoryUrlField;
    @FXML private Label gerritProjectStatusLabel;
    @FXML private TextField productionRootField;
    @FXML private ComboBox<TriggerSettings.Event> triggerEventComboBox;
    @FXML private ComboBox<TriggerSettings.MatchType> branchMatchTypeComboBox;
    @FXML private TextField branchPatternField;
    @FXML private CheckBox scmPollingCheckBox;
    @FXML private VBox scmPollingOptions;
    @FXML private TextField scmPollingScheduleField;
    @FXML private Label deploymentDetectionSummary;
    @FXML private VBox frontendDeploymentOptions;
    @FXML private ComboBox<FrontendDeploymentMode> frontendDeploymentModeComboBox;
    @FXML private VBox iisSiteOptions;
    @FXML private TextField iisSiteNameField;
    @FXML private TextField frontendPortField;
    @FXML private Label frontendPortStatusLabel;
    @FXML private VBox backendDeploymentOptions;
    @FXML private TextField backendPortField;
    @FXML private Label backendServiceNameLabel;
    @FXML private Label reverseProxyHintLabel;
    @FXML private Label backendPortStatusLabel;
    @FXML private VBox productionConfigOptions;
    @FXML private TextField databaseUrlField;
    @FXML private Label databaseUrlHintLabel;
    @FXML private TextField databaseUsernameField;
    @FXML private PasswordField databasePasswordField;
    @FXML private PasswordField jwtSecretField;
    @FXML private ListView<String> jobPreviewList;
    @FXML private Button previewButton;
    @FXML private Button applyButton;
    @FXML private CheckBox confirmationCheckBox;
    @FXML private CheckBox customPipelineCheckBox;
    @FXML private VBox customPipelineOptions;
    @FXML private TextArea customFrontendPipelineArea;
    @FXML private TextArea customBackendPipelineArea;
    @FXML private VBox deploymentProgressPane;
    @FXML private Label deploymentProgressLabel;
    @FXML private ProgressBar deploymentProgressBar;
    @FXML private Button refreshDashboardButton;
    @FXML private Label dashboardBackendCountLabel;
    @FXML private Label dashboardFrontendCountLabel;
    @FXML private Label dashboardRunningCountLabel;
    @FXML private ListView<ManagedRuntime> backendRuntimeList;
    @FXML private ListView<ManagedRuntime> frontendRuntimeList;
    @FXML private Button startBackendButton;
    @FXML private Button stopBackendButton;
    @FXML private Button deleteBackendButton;
    @FXML private Button startFrontendButton;
    @FXML private Button stopFrontendButton;
    @FXML private Button deleteFrontendButton;

    private HostSnapshot host;
    private EnvConfig config;
    private ProjectProfile projectProfile;
    private String resolvedGerritProjectName = "";
    private long gerritLookupVersion;
    private String lastError = "";
    private boolean dashboardVisible;
    private boolean dashboardBusy;

    @FXML
    private void initialize() {
        jobPreviewList.setItems(FXCollections.observableArrayList());
        backendRuntimeList.setItems(FXCollections.observableArrayList());
        frontendRuntimeList.setItems(FXCollections.observableArrayList());
        configureRuntimeList(backendRuntimeList);
        configureRuntimeList(frontendRuntimeList);
        backendRuntimeList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateDashboardActions());
        frontendRuntimeList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateDashboardActions());
        TriggerSettings defaults = TriggerSettings.defaults();
        triggerEventComboBox.getItems().setAll(TriggerSettings.Event.values());
        triggerEventComboBox.setValue(defaults.event());
        branchMatchTypeComboBox.getItems().setAll(TriggerSettings.MatchType.values());
        branchMatchTypeComboBox.setValue(defaults.branchMatchType());
        branchPatternField.setText(defaults.branchPattern());
        scmPollingCheckBox.setSelected(defaults.scmPollingEnabled());
        scmPollingScheduleField.setText(defaults.scmPollingSchedule());
        updateScmPollingOptions();
        frontendDeploymentModeComboBox.getItems().setAll(FrontendDeploymentMode.values());
        frontendDeploymentModeComboBox.setValue(FrontendDeploymentMode.STATIC_DIRECTORY);
        backendPortField.setText("8080");
        frontendPortField.setText("80");
        updateCustomPipelineOptions();
        updateDeploymentOptionVisibility();
        installDriveComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(StorageVolume volume) {
                return volume == null ? "" : volumeChoiceText(volume);
            }

            @Override
            public StorageVolume fromString(String value) {
                return null;
            }
        });
        gerritLookupDelay.setOnFinished(event -> resolveGerritProject());
        gerritProjectField.textProperty().addListener((observable, oldValue, newValue) -> scheduleGerritLookup(newValue));
        triggerEventComboBox.valueProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        branchMatchTypeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        branchPatternField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        scmPollingScheduleField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        backendPortField.textProperty().addListener((observable, oldValue, newValue) -> {
            updateAutoDeploymentSummary();
            updatePortAvailability();
            invalidateJobPreview();
        });
        frontendPortField.textProperty().addListener((observable, oldValue, newValue) -> {
            updatePortAvailability();
            invalidateJobPreview();
        });
        iisSiteNameField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        databaseUrlField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        databaseUrlField.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                normalizeDatabaseUrlFieldQuietly();
            }
        });
        databaseUsernameField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        databasePasswordField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        jwtSecretField.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        customFrontendPipelineArea.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        customBackendPipelineArea.textProperty().addListener((observable, oldValue, newValue) -> invalidateJobPreview());
        setBusy(false);
        showOnboarding();
        runAsync(this::bootstrap, ignored -> checkExistingAgent());
    }

    @FXML
    private void handleShowAgentStep() {
        showOnboarding();
    }

    @FXML
    private void handleShowDashboard() {
        dashboardVisible = true;
        setPaneVisible(dashboardPane, true);
        setPaneVisible(onboardingPane, false);
        setPaneVisible(projectPane, false);
        setPaneVisible(deploymentPane, false);
        updateNavigation();
        contentScrollPane.setVvalue(0);
        footerStatus.setText("正在读取本机部署状态…");
        refreshDashboard();
    }

    @FXML
    private void handleRefreshDashboard() {
        refreshDashboard();
    }

    @FXML
    private void handleStartBackend() {
        controlRuntime(backendRuntimeList.getSelectionModel().getSelectedItem(), true);
    }

    @FXML
    private void handleStopBackend() {
        controlRuntime(backendRuntimeList.getSelectionModel().getSelectedItem(), false);
    }

    @FXML
    private void handleStartFrontend() {
        controlRuntime(frontendRuntimeList.getSelectionModel().getSelectedItem(), true);
    }

    @FXML
    private void handleStopFrontend() {
        controlRuntime(frontendRuntimeList.getSelectionModel().getSelectedItem(), false);
    }

    @FXML
    private void handleRenameBackend() {
        ManagedRuntime runtime = backendRuntimeList.getSelectionModel().getSelectedItem();
        if (runtime == null || dashboardBusy) {
            footerStatus.setText("请先选择要重命名的后端服务");
            return;
        }
        TextInputDialog dialog = new TextInputDialog(runtime.displayName());
        dialog.setTitle("重命名后端服务");
        dialog.setHeaderText("仅修改显示名称，稳定服务 ID 保持不变");
        dialog.setContentText("新名称：");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("assistant-dialog");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        setDashboardBusy(true);
        footerStatus.setText("正在重命名" + runtime.displayName() + "…");
        runDashboardAsync(
                () -> {
                    localRuntimeDashboardService.renameBackend(runtime, result.get());
                    return localRuntimeDashboardService.list();
                },
                runtimes -> {
                    applyDashboardSnapshot(runtimes);
                    setDashboardBusy(false);
                    footerStatus.setText("后端服务显示名称已更新");
                    showBrandedNoticeDialog(
                            "重命名完成",
                            "服务显示名称已更新",
                            "Windows 服务 ID 仍为 " + runtime.id() + "，不会影响 Jenkins 流水线。",
                            "右键服务可再次修改显示名称。",
                            "知道了");
                });
    }

    @FXML
    private void handleDeleteBackend() {
        deleteRuntime(backendRuntimeList.getSelectionModel().getSelectedItem(), "Windows 服务");
    }

    @FXML
    private void handleDeleteFrontend() {
        deleteRuntime(frontendRuntimeList.getSelectionModel().getSelectedItem(), "IIS 站点");
    }

    @FXML
    private void handleShowProjectStep() {
        showProjectSelection();
    }

    @FXML
    private void handleShowDeploymentStep() {
        showDeployment();
    }

    private void refreshDashboard() {
        if (dashboardBusy) {
            return;
        }
        setDashboardBusy(true);
        runDashboardAsync(
                localRuntimeDashboardService::list,
                runtimes -> {
                    applyDashboardSnapshot(runtimes);
                    setDashboardBusy(false);
                    footerStatus.setText("本机服务状态已刷新");
                });
    }

    private void controlRuntime(ManagedRuntime runtime, boolean start) {
        if (runtime == null || dashboardBusy) {
            return;
        }
        String action = start ? "启动" : "停止";
        String consequence = start
                ? "启动后将恢复系统自动启动。"
                : "停止后将禁止系统自动启动，后续 Jenkins 部署也会保持停机。";
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "确定要" + action + "“" + runtime.displayName() + "”吗？\n" + consequence,
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.setHeaderText("确认" + action + "本机服务");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        setDashboardBusy(true);
        footerStatus.setText("正在" + action + runtime.displayName() + "…");
        runDashboardAsync(
                () -> {
                    if (start) {
                        localRuntimeDashboardService.start(runtime);
                    } else {
                        localRuntimeDashboardService.stop(runtime);
                    }
                    return localRuntimeDashboardService.list();
                },
                runtimes -> {
                    applyDashboardSnapshot(runtimes);
                    setDashboardBusy(false);
                    footerStatus.setText(runtime.displayName() + "已" + action);
                    showBrandedNoticeDialog(
                            "操作完成",
                            runtime.displayName() + "已" + action,
                            start ? "该服务已经启动，并恢复为允许系统自动启动。" : "该服务已经停止，并已禁止系统自动启动。",
                            "这是本机服务看板操作，不代表 Jenkins 流水线部署成功。",
                            "知道了");
                });
    }

    private void deleteRuntime(ManagedRuntime runtime, String kindLabel) {
        if (runtime == null || dashboardBusy) {
            footerStatus.setText("请先选择要删除的" + kindLabel);
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "确认删除吗？",
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.setTitle("删除");
        confirm.setHeaderText(null);
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
        confirm.getDialogPane().getStyleClass().add("assistant-dialog");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        setDashboardBusy(true);
        footerStatus.setText("正在删除" + runtime.displayName() + "…");
        runDashboardAsync(
                () -> {
                    localRuntimeDashboardService.delete(runtime);
                    return localRuntimeDashboardService.list();
                },
                runtimes -> {
                    applyDashboardSnapshot(runtimes);
                    setDashboardBusy(false);
                    footerStatus.setText(runtime.displayName() + "已删除，生产文件已保留");
                    showBrandedNoticeDialog(
                            "删除完成",
                            runtime.displayName() + "已从系统中删除",
                            kindLabel + "注册已移除，生产目录和部署文件未删除。",
                            "需要恢复时，可重新执行对应的 Jenkins 部署。",
                            "知道了");
                });
    }

    private void applyDashboardSnapshot(List<ManagedRuntime> runtimes) {
        ManagedRuntime selectedBackend = backendRuntimeList.getSelectionModel().getSelectedItem();
        ManagedRuntime selectedFrontend = frontendRuntimeList.getSelectionModel().getSelectedItem();
        List<ManagedRuntime> backend = runtimes.stream()
                .filter(runtime -> runtime.kind() == ManagedRuntime.Kind.BACKEND_SERVICE)
                .toList();
        List<ManagedRuntime> frontend = runtimes.stream()
                .filter(runtime -> runtime.kind() == ManagedRuntime.Kind.IIS_SITE)
                .toList();
        backendRuntimeList.getItems().setAll(backend);
        frontendRuntimeList.getItems().setAll(frontend);
        restoreRuntimeSelection(backendRuntimeList, selectedBackend);
        restoreRuntimeSelection(frontendRuntimeList, selectedFrontend);
        dashboardBackendCountLabel.setText(Integer.toString(backend.size()));
        dashboardFrontendCountLabel.setText(Integer.toString(frontend.size()));
        long running = runtimes.stream().filter(runtime -> runtime.state() == ManagedRuntime.State.RUNNING).count();
        dashboardRunningCountLabel.setText(Long.toString(running));
        updateDashboardActions();
    }

    private static void restoreRuntimeSelection(ListView<ManagedRuntime> list, ManagedRuntime previous) {
        if (previous == null) {
            return;
        }
        list.getItems().stream()
                .filter(runtime -> runtime.kind() == previous.kind() && runtime.id().equals(previous.id()))
                .findFirst()
                .ifPresent(runtime -> list.getSelectionModel().select(runtime));
    }

    private void configureRuntimeList(ListView<ManagedRuntime> list) {
        list.setPlaceholder(new Label("暂无由本软件管理的服务"));
        list.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.SECONDARY) {
                return;
            }
            Node node = event.getPickResult().getIntersectedNode();
            while (node != null && !(node instanceof ListCell<?>)) {
                node = node.getParent();
            }
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                list.getSelectionModel().select(cell.getIndex());
            }
        });
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(ManagedRuntime runtime, boolean empty) {
                super.updateItem(runtime, empty);
                setText(null);
                setGraphic(null);
                if (empty || runtime == null) {
                    return;
                }
                Label name = new Label(runtime.displayName());
                name.getStyleClass().add("runtime-name");
                String statusText = switch (runtime.state()) {
                    case RUNNING -> "运行中";
                    case STOPPED -> "已停止";
                    case UNKNOWN -> "状态未知";
                };
                Label status = new Label(statusText + (runtime.autoStart() ? " · 允许自启" : " · 已禁止自启"));
                status.getStyleClass().add(switch (runtime.state()) {
                    case RUNNING -> "runtime-running";
                    case STOPPED -> "runtime-stopped";
                    case UNKNOWN -> "runtime-unknown";
                });
                Label detail = new Label(runtime.detail());
                detail.getStyleClass().add("runtime-detail");
                detail.setWrapText(true);
                VBox content = new VBox(4, name, status, detail);
                Label addressHint = new Label(runtime.addresses().isEmpty()
                        ? "访问地址未识别，请核对服务监听或站点绑定配置"
                        : "配置访问地址（可复制，未验证连通性）");
                addressHint.getStyleClass().add("runtime-detail");
                addressHint.setWrapText(true);
                content.getChildren().add(addressHint);
                for (String address : runtime.addresses()) {
                    TextField field = new TextField(address);
                    field.setEditable(false);
                    field.setPrefColumnCount(22);
                    field.setAccessibleText("配置访问地址：" + address);
                    content.getChildren().add(field);
                }
                if (runtime.kind() == ManagedRuntime.Kind.BACKEND_SERVICE
                        && runtime.state() == ManagedRuntime.State.STOPPED && runtime.autoStart()) {
                    Label stoppedHint = new Label("已停止但允许自启：请检查 backend/service/logs 中的运行日志");
                    stoppedHint.setWrapText(true);
                    stoppedHint.getStyleClass().add("runtime-detail");
                    content.getChildren().add(stoppedHint);
                }
                setGraphic(content);
            }
        });
    }

    private void setDashboardBusy(boolean busy) {
        dashboardBusy = busy;
        refreshDashboardButton.setDisable(busy);
        updateDashboardActions();
    }

    private void updateDashboardActions() {
        updateRuntimeActions(
                backendRuntimeList.getSelectionModel().getSelectedItem(),
                startBackendButton,
                stopBackendButton,
                deleteBackendButton);
        updateRuntimeActions(
                frontendRuntimeList.getSelectionModel().getSelectedItem(),
                startFrontendButton,
                stopFrontendButton,
                deleteFrontendButton);
    }

    private void updateRuntimeActions(ManagedRuntime runtime, Button startButton, Button stopButton, Button deleteButton) {
        boolean missing = dashboardBusy || runtime == null;
        startButton.setDisable(missing || (runtime.state() == ManagedRuntime.State.RUNNING && runtime.autoStart()));
        stopButton.setDisable(missing || (runtime.state() == ManagedRuntime.State.STOPPED && !runtime.autoStart()));
        deleteButton.setDisable(missing);
    }

    private <T> void runDashboardAsync(CheckedSupplier<T> supplier, java.util.function.Consumer<T> onSuccess) {
        executor.submit(() -> {
            try {
                T result = supplier.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    setDashboardBusy(false);
                    String message = Optional.ofNullable(exception.getMessage()).filter(value -> !value.isBlank())
                            .orElse("未知错误");
                    lastError = diagnosticsService.describe(exception, config == null ? new EnvConfig() : config);
                    footerStatus.setText("服务看板操作失败：" + message);
                    showBrandedErrorDialog("服务看板操作失败", message, exception);
                });
            }
        });
    }

    @FXML
    private void handleConnectionSettings() {
        if (deploymentBusy) { return; }
        try {
            EnvConfig current = config == null ? configService.load() : config.copy();
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Jenkins 连接设置");
            dialog.setHeaderText("只需首次填写，之后软件会自动读取");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            TextField url = new TextField(current.getOrDefault("JENKINS_URL", ""));
            TextField username = new TextField(current.getOrDefault("JENKINS_USERNAME", ""));
            PasswordField password = new PasswordField();
            password.setPromptText(current.get("JENKINS_PASSWORD").isPresent() ? "已保存，留空表示不修改" : "请输入密码或 API Token");
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(12);
            addField(grid, 0, "Jenkins 地址", url);
            addField(grid, 1, "Jenkins 账号", username);
            addField(grid, 2, "Jenkins 密码", password);
            dialog.getDialogPane().setContent(grid);

            if (dialog.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
                current.put("JENKINS_URL", url.getText());
                current.put("JENKINS_USERNAME", username.getText());
                if (!password.getText().isBlank()) {
                    current.put("JENKINS_PASSWORD", password.getText());
                }
                configService.save(current);
                config = current;
                footerStatus.setText("连接设置已保存");
                checkExistingAgent();
            }
        } catch (Exception exception) {
            showError("保存设置失败", exception);
        }
    }

    @FXML
    private void handleProvisionAgent() {
        if (installDriveComboBox.getValue() == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setHeaderText("请先选择安装磁盘");
            alert.setContentText("Agent、Java Runtime、WinSW 和工作目录会安装到您选择的磁盘。");
            alert.showAndWait();
            return;
        }
        if (!hasConnectionSettings()) {
            handleConnectionSettings();
            if (!hasConnectionSettings()) {
                return;
            }
        }
        setBusy(true);
        lastError = "";
        runAsync(
                () -> {
                    NodeStatus status = agentProvisioningService.provision(host, progress -> Platform.runLater(() -> {
                        setupProgress.setProgress((double) progress.step() / progress.totalSteps());
                        currentStepTitle.setText(progress.title());
                        currentStepDetail.setText(progress.detail());
                        footerStatus.setText(progress.step() + "/" + progress.totalSteps() + "  " + progress.title());
                    }));
                    config = configService.load();
                    return status;
                },
                status -> {
                    setBusy(false);
                    markAgentOnline(status);
                    showProjectSelectionAndRestore();
                });
    }

    @FXML
    private void handleRetry() {
        checkExistingAgent();
    }

    @FXML
    private void handleInstallDriveChanged() {
        if (config != null && AppPaths.isExternalAgent(config)) { return; }
        StorageVolume selected = installDriveComboBox.getValue();
        if (selected == null || config == null) {
            installPathLabel.setText("尚未选择，不会默认安装到 C 盘");
            provisionButton.setDisable(true);
            return;
        }
        try {
            Path installDirectory = AppPaths.agentDirectoryForRoot(selected.root());
            config.put("AGENT_INSTALL_DIR", installDirectory.toString());
            configService.save(config);
            installPathLabel.setText("安装位置：" + installDirectory);
            provisionButton.setDisable(false);
            footerStatus.setText("已选择安装磁盘 " + selected.root());
        } catch (Exception exception) {
            showError("保存安装磁盘失败", exception);
        }
    }

    @FXML
    private void handleChooseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择项目源码目录");
        recentProjectService.existingDirectory(config)
                .ifPresent(path -> chooser.setInitialDirectory(path.toFile()));
        java.io.File selected = chooser.showDialog(projectPathField.getScene().getWindow());
        if (selected == null) {
            return;
        }
        resetProjectSelection();
        try {
            applyProjectSelection(selected.toPath(), true);
        } catch (Exception exception) {
            showError("项目识别失败", exception);
        }
    }

    private void resetProjectSelection() {
        projectProfile = null;
        wizardState.clearProject();
        updateNavigation();
        jobPreviewList.getItems().clear();
        confirmationCheckBox.setSelected(false);
        applyButton.setDisable(true);
    }

    private void applyProjectSelection(Path selectedDirectory, boolean remember) throws IOException {
        ProjectProfile detected = projectDetectionService.detect(selectedDirectory);
        if (remember) {
            recentProjectService.remember(config, detected.root());
        }
        projectProfile = detected;
        projectPathField.setText(projectProfile.root().toString());
        gerritProjectField.setText(projectProfile.projectName());
        frontendResultLabel.setText(projectProfile.frontendDirectory()
                .map(path -> "已识别：" + displayRelative(path) + " · " + projectProfile.frontendTool().orElse(""))
                .orElse("未识别到前端"));
        backendResultLabel.setText(projectProfile.backendDirectory()
                .map(path -> "已识别：" + displayRelative(path) + " · " + projectProfile.backendTool().orElse(""))
                .orElse("未识别到后端"));
        applyDeploymentHints();
        deploymentProjectSummaryLabel.setText(projectSummary());
        wizardState.markProjectReady();
        showDeployment();
    }

    @FXML
    private void handleChooseProductionRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择文件夹放置生产文件");
        Path initialDirectory = productionInitialDirectory();
        if (initialDirectory != null) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }
        java.io.File selected = chooser.showDialog(productionRootField.getScene().getWindow());
        if (selected == null) {
            return;
        }
        try {
            Path prepared = productionDirectoryService.prepare(selected.toPath());
            productionRootField.setText(prepared.toString());
            invalidateJobPreview();
            footerStatus.setText("生产目录已准备：" + prepared);
        } catch (Exception exception) {
            showError("创建生产目录失败", exception);
        }
    }

    @FXML
    private void handleScmPollingChanged() {
        updateScmPollingOptions();
        invalidateJobPreview();
    }

    @FXML
    private void handleFrontendDeploymentModeChanged() {
        updateDeploymentOptionVisibility();
        invalidateJobPreview();
    }

    @FXML
    private void handleCustomPipelineChanged() {
        updateCustomPipelineOptions();
        invalidateJobPreview();
    }

    @FXML
    private void handlePreviewJobs() {
        try {
            requireResolvedGerritProject();
            DeploymentSettings settings = deploymentSettings();
            validateProductionRuntimeConfig();
            List<JobDraft> drafts = jobService().preview(requireProject(), settings);
            jobPreviewList.getItems().setAll(drafts.stream()
                    .map(draft -> previewDescription(draft, settings))
                    .toList());
            applyButton.setDisable(!confirmationCheckBox.isSelected());
            footerStatus.setText("预览已生成，未修改 Jenkins");
        } catch (Exception exception) {
            showError("生成预览失败", exception);
        }
    }

    @FXML
    private void handleConfirmationChanged() {
        applyButton.setDisable(deploymentBusy || !confirmationCheckBox.isSelected() || jobPreviewList.getItems().isEmpty());
    }

    @FXML
    private void handleApplyJobs() {
        if (deploymentBusy || !confirmationCheckBox.isSelected()) {
            return;
        }
        ProjectProfile selectedProject;
        DeploymentSettings settings;
        ProductionRuntimeConfig runtimeConfig;
        try {
            requireResolvedGerritProject();
            selectedProject = requireProject();
            settings = deploymentSettings();
            runtimeConfig = productionRuntimeConfig();
            if (runtimeConfig != null) {
                productionConfigService.validate(runtimeConfig);
            }
        } catch (Exception exception) {
            showError("生产环境配置不完整", exception);
            return;
        }
        setBusy(true);
        setPaneVisible(deploymentProgressPane, true);
        deploymentProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        deploymentProgressLabel.setText("正在检查任务身份、构建状态和首次部署进度…");
        JobProvisioningService service = jobService();
        runAsync(() -> service.plan(selectedProject, settings), plan -> {
            setBusy(false);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, plan.summary()
                    + "\n\n将按后端 → 前端继续首次部署，已成功的部分不重复运行。"
                    + "\n修改或认领旧版失败任务前，会将原配置备份到：" + AppPaths.dataDirectory().resolve("job-backups")
                    + "\n失败任务会重新执行流水线；如上次已进入生产部署，请先确认回滚状态。"
                    + "\n正在构建、来源不明或已成功的脚本修改仍会被阻止。是否执行上述操作？", ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("确认创建 / 继续首次部署");
            if (applyButton.getScene() != null) { confirm.initOwner(applyButton.getScene().getWindow()); }
            if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
                deploymentProgressBar.setProgress(0);
                deploymentProgressLabel.setText("已取消，未创建或修改 Jenkins 任务");
                return;
            }
            try {
                ProductionRuntimeConfig currentRuntime = productionRuntimeConfig();
                if (!settings.equals(deploymentSettings()) || !selectedProject.equals(requireProject())
                        || !sameRuntimeConfig(runtimeConfig, currentRuntime)) {
                    throw new IllegalStateException("检测期间部署参数发生变化，未执行任务操作。");
                }
            } catch (Exception changed) {
                showError("请重新生成预览", changed);
                return;
            }
            executeDeploymentPlan(service, plan, selectedProject, settings, runtimeConfig);
        });
    }

    private static boolean sameRuntimeConfig(ProductionRuntimeConfig first, ProductionRuntimeConfig second) {
        if (first == null || second == null) { return first == second; }
        return first.databaseUrl().equals(second.databaseUrl()) && first.databaseUsername().equals(second.databaseUsername())
                && first.databasePassword().equals(second.databasePassword()) && first.jwtSecret().equals(second.jwtSecret());
    }

    private void executeDeploymentPlan(JobProvisioningService service, JobPreparationPlan plan,
                                       ProjectProfile selectedProject, DeploymentSettings settings, ProductionRuntimeConfig runtimeConfig) {
        setBusy(true);
        deploymentProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        deploymentProgressLabel.setText("正在复核已确认的续建计划…");
        runAsync(
                () -> {
                    service.validatePlan(plan, settings);
                    agentProvisioningService.prepareDeploymentSupport(config);
                    boolean backendWillRun = plan.items().stream().anyMatch(item -> "backend".equals(item.draft().kind())
                            && item.action() != JobPreparationPlan.Action.SKIP_SUCCESS);
                    if (runtimeConfig != null && backendWillRun) {
                        productionConfigService.write(settings.productionRoot(), settings.backendPort(), runtimeConfig);
                    }
                    DeploymentRunResult result = service.applyAndDeploy(plan, settings, this::updateDeploymentProgress);
                    return new DeploymentCompletion(result,
                            redisConfigDetector.shouldRemind(result, selectedProject, settings.productionRoot()));
                },
                result -> {
                    setBusy(false);
                    deploymentProgressBar.setProgress(1);
                    handleDeploymentResult(result.deployment(), result.redisConfigured());
                });
    }

    private void updateDeploymentProgress(DeploymentProgress progress) {
        Platform.runLater(() -> {
            setPaneVisible(deploymentProgressPane, true);
            deploymentProgressBar.setProgress(progress.fraction());
            deploymentProgressLabel.setText(progress.title() + " · " + progress.detail());
            footerStatus.setText(progress.title() + "：" + progress.detail());
        });
    }

    private void handleDeploymentResult(DeploymentRunResult result, boolean redisConfigured) {
        if (result.successful()) {
            String builds = result.outcomes().stream()
                    .map(outcome -> outcome.jobName() + " #" + outcome.buildNumber() + (outcome.attempts() == 0 ? "（沿用成功结果）" : ""))
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("无构建");
            currentStepTitle.setText("部署成功");
            currentStepDetail.setText(builds);
            footerStatus.setText("已完成 " + result.outcomes().size() + " 个 Job 的首次部署 / 续建");
            showBrandedSuccessDialog("部署成功", "Jenkins 前后端流水线均已部署成功：" + builds);
            if (redisConfigured) {
                showBrandedNoticeDialog("依赖服务提示", "请启动或确认 Redis 已运行",
                        "检测到该项目配置了 Redis。请按项目配置启动 Redis，并确认后端可以连接。"
                                + "\n如果使用远程 Redis，请确认远程服务可用，无需在本机重复启动。",
                        "此提示仅基于配置，不代表 Redis 当前未运行；软件不会自动安装或启动 Redis，也不会展示连接密码。",
                        "知道了");
            }
            return;
        }

        DeploymentOutcome failure = result.failure();
        String detail = failure == null
                ? "Jenkins 首次部署未完整执行，请导出诊断。"
                : failure.jobName() + " 构建 #" + failure.buildNumber() + "：" + failure.message();
        currentStepTitle.setText("首次部署未完成，可修复后继续");
        currentStepDetail.setText(detail);
        footerStatus.setText(detail);
        lastError = detail + System.lineSeparator()
                + (failure == null ? "" : failure.consoleText());
        showBrandedErrorDialog("首次部署未完成", detail + "\n已保留输入和已有任务；修复问题后可重新预览并继续，无需删除 Job 或更换项目名。", null);
    }

    private void showBrandedSuccessDialog(String title, String message) {
        ImageView celebration = new ImageView(new Image(
                getClass().getResource("/icons/deployment-success.png").toExternalForm()));
        celebration.setFitWidth(96);
        celebration.setFitHeight(96);
        celebration.setPreserveRatio(true);
        celebration.setSmooth(true);
        celebration.setAccessibleText("部署成功庆祝图片");
        showBrandedNoticeDialog(
                "部署完成",
                title,
                message,
                "后续 Gerrit 提交将按照右侧触发规则自动执行，不需要再登录 Jenkins 手动运行。",
                "完成",
                celebration);
    }

    private void showBrandedNoticeDialog(
            String kickerText,
            String title,
            String message,
            String helpText,
            String buttonText) {
        showBrandedNoticeDialog(kickerText, title, message, helpText, buttonText, null);
    }

    private void showBrandedNoticeDialog(
            String kickerText,
            String title,
            String message,
            String helpText,
            String buttonText,
            Node headerGraphic) {
        ButtonType dismissButton = new ButtonType(buttonText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Jenkins一键部署助手");
        dialog.initModality(Modality.WINDOW_MODAL);
        if (applyButton.getScene() != null) {
            dialog.initOwner(applyButton.getScene().getWindow());
        }
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().setGraphic(null);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("assistant-dialog");
        dialog.getDialogPane().getButtonTypes().add(dismissButton);
        Label kicker = new Label(kickerText);
        kicker.getStyleClass().add("dialog-kicker");
        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label detail = new Label(message);
        detail.setWrapText(true);
        detail.setMinHeight(Region.USE_PREF_SIZE);
        detail.getStyleClass().add("dialog-message");
        Label nextStep = new Label(helpText);
        nextStep.setWrapText(true);
        nextStep.setMinHeight(Region.USE_PREF_SIZE);
        nextStep.getStyleClass().add("dialog-help");
        VBox content = new VBox(10);
        if (headerGraphic == null) {
            content.getChildren().addAll(kicker, heading);
        } else {
            HBox header = new HBox(16, headerGraphic, new VBox(10, kicker, heading));
            header.setAlignment(Pos.TOP_LEFT);
            content.getChildren().add(header);
        }
        content.getChildren().addAll(detail, nextStep);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(540);
        ((Button) dialog.getDialogPane().lookupButton(dismissButton)).getStyleClass().add("button-primary");
        dialog.showAndWait();
    }

    @FXML
    private void handleExportDiagnostics() {
        try {
            Path result = diagnosticsService.export(
                    AppPaths.diagnosticsDirectory(),
                    host,
                    config == null ? new EnvConfig() : config,
                    currentStepTitle.getText(),
                    lastError);
            footerStatus.setText("诊断文件已导出：" + result);
        } catch (IOException exception) {
            showError("导出诊断失败", exception);
        }
    }

    private Void bootstrap() throws IOException {
        host = systemProbeService.inspect();
        config = configService.load();
        boolean changed = importLegacyCredentials(config);
        changed |= DefaultJenkinsSettings.applyMissing(config);
        if (config.get("JDA_JOB_OWNER_ID").isEmpty()) {
            config.put("JDA_JOB_OWNER_ID", java.util.UUID.randomUUID().toString());
            changed = true;
        }
        if (changed) {
            configService.save(config);
        }
        Platform.runLater(() -> {
            hostNameLabel.setText(host.hostName());
            osLabel.setText(host.windowsVersion());
            architectureLabel.setText(host.architecture());
            renderStorageVolumes();
            agentNameLabel.setText(config.getOrDefault("AGENT_NAME", "将自动生成"));
            currentStepTitle.setText("本机检测完成");
            currentStepDetail.setText(host.administrator() ? "已获得管理员权限" : "安装服务时需要管理员权限");
            setupProgress.setProgress(1.0 / 7.0);
        });
        return null;
    }

    private boolean importLegacyCredentials(EnvConfig target) throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("账号密码.txt").toAbsolutePath().normalize());
        candidates.add(AppPaths.dataDirectory().resolve("账号密码.txt"));
        String applicationPath = System.getProperty("jpackage.app-path", "");
        if (!applicationPath.isBlank()) {
            Path parent = Path.of(applicationPath).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                candidates.add(parent.resolve("账号密码.txt"));
            }
        }

        LegacyCredentialImporter importer = new LegacyCredentialImporter();
        for (Path candidate : candidates.stream().distinct().toList()) {
            if (importer.importIfPresent(candidate, target)) {
                return true;
            }
        }
        return false;
    }

    private void checkExistingAgent() {
        if (config == null) {
            return;
        }
        if (!hasConnectionSettings()) {
            connectionLabel.setText("等待设置 Jenkins 地址");
            currentStepTitle.setText("请先完成连接设置");
            currentStepDetail.setText("请打开连接设置检查 Jenkins 地址和账号");
            return;
        }
        setBusy(true);
        runAsync(() -> {
            JenkinsClient client = createJenkinsClient();
            long millis = client.pingMillis();
            var recovered = agentProvisioningService.recoverOnlineAgent(host, client, ignored -> { });
            if (recovered.isPresent()) {
                config = configService.load();
                agentProvisioningService.refreshBundledSupport(config);
                return new ConnectionCheck(millis, recovered.get());
            }
            String agentName = config.getOrDefault("AGENT_NAME", "");
            NodeStatus status = agentName.isBlank() || !client.nodeExists(agentName)
                    ? NodeStatus.offline(agentName)
                    : client.nodeStatus(agentName);
            return new ConnectionCheck(millis, status);
        }, result -> {
            setBusy(false);
            if (AppPaths.isExternalAgent(config)) {
                agentNameLabel.setText(config.getOrDefault("AGENT_NAME", ""));
                installPathLabel.setText("已有 Agent：" + config.getOrDefault("AGENT_INSTALL_DIR", ""));
            }
            connectionLabel.setText("Jenkins 连接正常 · " + result.responseMillis + " ms");
            if (result.nodeStatus.online()) {
                markAgentOnline(result.nodeStatus);
                showProjectSelectionAndRestore();
            } else {
                wizardState.markAgentOffline();
                showOnboarding();
                if (AppPaths.isExternalAgent(config)) {
                    headerStatus.setText("已有 Agent · 离线");
                    headerStatus.getStyleClass().remove("status-ok");
                    if (!headerStatus.getStyleClass().contains("status-warn")) { headerStatus.getStyleClass().add("status-warn"); }
                    currentStepTitle.setText("已发现本机 Agent，暂未在线");
                    currentStepDetail.setText("请检查原 Windows 服务和 Jenkins 连接，点击“查看本机 Agent 状态”重新检测；无需重复安装");
                } else {
                    currentStepTitle.setText("Jenkins 已连接，Agent 待配置");
                    currentStepDetail.setText("点击下方按钮，其余步骤将自动完成");
                }
            }
        });
    }

    private void markAgentOnline(NodeStatus status) {
        wizardState.markAgentOnline();
        agentNameLabel.setText(status.displayName());
        headerStatus.setText("Agent 已在线");
        headerStatus.getStyleClass().remove("status-warn");
        if (!headerStatus.getStyleClass().contains("status-ok")) {
            headerStatus.getStyleClass().add("status-ok");
        }
        setupProgress.setProgress(1);
        currentStepTitle.setText("Agent 配置完成");
        currentStepDetail.setText(AppPaths.isExternalAgent(config) ? "已识别并复用原有 Agent，未修改其配置或重启服务" : "已通过 WebSocket 连接 Jenkins");
    }

    private JobProvisioningService jobService() {
        return new JobProvisioningService(createJenkinsClient(), config.get("JDA_JOB_OWNER_ID").orElseThrow(),
                AppPaths.dataDirectory().resolve("job-backups"));
    }

    private JenkinsClient createJenkinsClient() {
        return new JenkinsClient(
                config.get("JENKINS_URL").orElseThrow(),
                config.get("JENKINS_USERNAME").orElseThrow(),
                config.get("JENKINS_PASSWORD").orElseThrow());
    }

    private DeploymentSettings deploymentSettings() {
        TriggerSettings triggerSettings = new TriggerSettings(
                triggerEventComboBox.getValue(),
                branchMatchTypeComboBox.getValue(),
                branchPatternField.getText(),
                scmPollingCheckBox.isSelected(),
                scmPollingScheduleField.getText());
        return new DeploymentSettings(
                resolvedGerritProjectName,
                repositoryUrlField.getText().strip(),
                GerritClient.JENKINS_SERVER_NAME,
                config.getOrDefault("AGENT_LABEL", config.getOrDefault("AGENT_NAME", "")),
                preparedProductionRoot(),
                triggerSettings,
                frontendDeploymentModeComboBox.getValue(),
                iisSiteNameField.getText().strip(),
                frontendPort(),
                backendPort(),
                BackendServiceNaming.serviceId(resolvedGerritProjectName),
                configuredBackendWinSw(),
                configuredBackendJava(),
                requireProject().deploymentHints().backendHealthPath(),
                configuredIisSupportDirectory(),
                customPipelineCheckBox.isSelected() ? customFrontendPipelineArea.getText() : "",
                customPipelineCheckBox.isSelected() ? customBackendPipelineArea.getText() : "");
    }

    private int frontendPort() {
        if (projectProfile == null || !projectProfile.hasFrontend()
                || frontendDeploymentModeComboBox.getValue() != FrontendDeploymentMode.IIS) {
            return 80;
        }
        int port = parsePort(frontendPortField.getText(), "前端端口");
        if (projectProfile.hasBackend() && port == backendPort()) {
            throw new IllegalArgumentException("前端端口和后端端口不能相同，否则 IIS 与后端服务会抢占同一端口。");
        }
        return port;
    }

    private int backendPort() {
        if (projectProfile == null || !projectProfile.hasBackend()) {
            return 8080;
        }
        return parsePort(backendPortField.getText(), "后端端口");
    }

    private ProductionRuntimeConfig productionRuntimeConfig() {
        if (projectProfile == null || !projectProfile.isJavaBackend()) {
            return null;
        }
        return new ProductionRuntimeConfig(
                normalizedDatabaseUrl(),
                databaseUsernameField.getText(),
                databasePasswordField.getText(),
                jwtSecretField.getText());
    }

    private void validateProductionRuntimeConfig() {
        ProductionRuntimeConfig runtimeConfig = productionRuntimeConfig();
        if (runtimeConfig != null) {
            productionConfigService.validate(runtimeConfig);
        }
    }

    private String normalizedDatabaseUrl() {
        String normalized = productionConfigService.normalizeDatabaseUrl(
                databaseUrlField.getText(),
                requireProject().deploymentHints().databaseJdbcUrl());
        if (!normalized.equals(databaseUrlField.getText())) {
            databaseUrlField.setText(normalized);
        }
        databaseUrlHintLabel.setText("已识别并补全为完整 JDBC 地址。");
        return normalized;
    }

    private void normalizeDatabaseUrlFieldQuietly() {
        if (projectProfile == null || !projectProfile.isJavaBackend()
                || databaseUrlField.getText() == null || databaseUrlField.getText().isBlank()) {
            return;
        }
        try {
            normalizedDatabaseUrl();
        } catch (IllegalArgumentException exception) {
            databaseUrlHintLabel.setText(exception.getMessage());
        }
    }

    private static int parsePort(String value, String label) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.strip());
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是 1 到 65535 之间的数字。");
        }
    }

    private Path configuredBackendWinSw() {
        if (projectProfile == null || !projectProfile.hasBackend()) {
            return null;
        }
        String installDirectory = config.getOrDefault("AGENT_INSTALL_DIR", "");
        if (installDirectory.isBlank()) {
            throw new IllegalStateException("未找到 Agent 安装目录，请先重新检测或配置本机 Agent。");
        }
        return AppPaths.deploymentSupportDirectory(config).resolve("jenkins-agent-service.exe");
    }

    private Path configuredBackendJava() {
        if (projectProfile == null || !projectProfile.isJavaBackend()) {
            return null;
        }
        if (AppPaths.isExternalAgent(config)) { return AppPaths.deploymentSupportDirectory(config).resolve("jre/bin/java.exe"); }
        String javaExecutable = config.getOrDefault("AGENT_JAVA_EXE", "");
        if (javaExecutable.isBlank()) {
            throw new IllegalStateException("未找到 Agent Java Runtime，请先重新检测或配置本机 Agent。");
        }
        return Path.of(javaExecutable).toAbsolutePath().normalize();
    }

    private Path configuredIisSupportDirectory() {
        if (projectProfile == null || !projectProfile.hasFrontend()
                || frontendDeploymentModeComboBox.getValue() != FrontendDeploymentMode.IIS) {
            return null;
        }
        String installDirectory = config.getOrDefault("AGENT_INSTALL_DIR", "");
        if (installDirectory.isBlank()) {
            throw new IllegalStateException("未找到 Agent 安装目录，请先重新检测或配置本机 Agent。");
        }
        return AppPaths.deploymentSupportDirectory(config).resolve("iis");
    }

    private Path requiredProductionRoot() {
        String value = productionRootField.getText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择文件夹放置生产文件。");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("生产目录必须是绝对路径。");
        }
        return path;
    }

    private Path preparedProductionRoot() {
        try {
            return productionDirectoryService.prepare(requiredProductionRoot());
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建生产目录结构，请检查文件夹权限。", exception);
        }
    }

    private Path productionInitialDirectory() {
        String current = productionRootField.getText();
        if (current != null && !current.isBlank()) {
            Path path = Path.of(current).toAbsolutePath().normalize();
            if (java.nio.file.Files.isDirectory(path)) {
                return path;
            }
        }
        if (projectProfile != null) {
            Path parent = projectProfile.root().getParent();
            if (parent != null && java.nio.file.Files.isDirectory(parent)) {
                return parent;
            }
        }
        return null;
    }

    private void updateScmPollingOptions() {
        boolean enabled = scmPollingCheckBox.isSelected();
        scmPollingOptions.setVisible(enabled);
        scmPollingOptions.setManaged(enabled);
    }

    private void updateCustomPipelineOptions() {
        boolean enabled = customPipelineCheckBox != null && customPipelineCheckBox.isSelected();
        if (customPipelineOptions != null) {
            setPaneVisible(customPipelineOptions, enabled);
        }
    }

    private void applyDeploymentHints() {
        var hints = requireProject().deploymentHints();
        frontendDeploymentModeComboBox.setValue(hints.frontendMode());
        backendPortField.setText(Integer.toString(hints.backendPort()));
        iisSiteNameField.setText(BackendServiceNaming.displayProjectName(projectProfile.projectName()));
        if (projectProfile.isJavaBackend()) {
            databaseUrlHintLabel.setText(hints.databaseJdbcUrl().isBlank()
                    ? "源码中未识别到 JDBC 模板，请填写完整 JDBC 地址。"
                    : "已识别源码中的数据库类型、库名和参数；可只填写 IP:端口，软件会自动补全。");
        } else {
            databaseUrlHintLabel.setText(".NET 生产配置由项目或 backend/config/appsettings.Production.json 提供。");
        }
        updateDeploymentOptionVisibility();
        updateAutoDeploymentSummary();
        invalidateJobPreview();
    }

    private void updateDeploymentOptionVisibility() {
        boolean hasFrontend = projectProfile != null && projectProfile.hasFrontend();
        boolean hasBackend = projectProfile != null && projectProfile.hasBackend();
        setPaneVisible(frontendDeploymentOptions, hasFrontend);
        setPaneVisible(backendDeploymentOptions, hasBackend);
        setPaneVisible(productionConfigOptions, projectProfile != null && projectProfile.isJavaBackend());
        boolean iisSelected = hasFrontend && frontendDeploymentModeComboBox.getValue() == FrontendDeploymentMode.IIS;
        setPaneVisible(iisSiteOptions, iisSelected);
        updateAutoDeploymentSummary();
        updatePortAvailability();
    }

    private void updateAutoDeploymentSummary() {
        if (projectProfile == null) {
            deploymentDetectionSummary.setText("选择源码后自动识别部署方式、后端端口和健康检查。");
            backendServiceNameLabel.setText("服务名称将按项目自动生成");
            reverseProxyHintLabel.setText("生产环境反向代理信息会在识别项目后显示。");
            return;
        }

        List<String> detected = new ArrayList<>();
        if (projectProfile.hasFrontend()) {
            detected.add("前端：" + frontendDeploymentModeComboBox.getValue());
        }
        if (projectProfile.hasBackend()) {
            String runtime = projectProfile.isDotNetBackend() ? ".NET 10" : "Java";
            detected.add("后端：" + runtime + " · 端口 " + displayBackendPort());
        }
        deploymentDetectionSummary.setText("已自动识别  " + String.join("  ·  ", detected));

        String projectName = resolvedGerritProjectName.isBlank()
                ? projectProfile.projectName()
                : resolvedGerritProjectName;
        backendServiceNameLabel.setText("Windows 服务：" + BackendServiceNaming.serviceId(projectName) + "（自动生成）");
        String proxyPath = projectProfile.deploymentHints().proxyPath();
        reverseProxyHintLabel.setText("生产环境请由 Nginx / IIS 将 " + proxyPath
                + " 转发到 http://127.0.0.1:" + displayBackendPort());
    }

    private void updatePortAvailability() {
        Integer frontendPort = validPortOrNull(frontendPortField.getText());
        Integer backendPort = validPortOrNull(backendPortField.getText());
        boolean frontendIsActive = projectProfile != null
                && projectProfile.hasFrontend()
                && frontendDeploymentModeComboBox.getValue() == FrontendDeploymentMode.IIS;
        boolean backendIsActive = projectProfile != null && projectProfile.hasBackend();

        if (frontendIsActive && backendIsActive && frontendPort != null && frontendPort.equals(backendPort)) {
            setPortStatus(frontendPortStatusLabel, "端口 " + frontendPort + " 与后端端口重复", "port-status-occupied");
            setPortStatus(backendPortStatusLabel, "端口 " + backendPort + " 与前端端口重复", "port-status-occupied");
            return;
        }

        updatePortStatus(frontendPortStatusLabel, frontendPortField.getText(), frontendIsActive, "前端");
        updatePortStatus(backendPortStatusLabel, backendPortField.getText(), backendIsActive, "后端");
    }

    private void updatePortStatus(Label label, String rawPort, boolean active, String role) {
        if (!active) {
            setPortStatus(label, "选择项目后检测" + role + "端口", "port-status-neutral");
            return;
        }
        Integer port = validPortOrNull(rawPort);
        if (port == null) {
            setPortStatus(label, "请输入 1–65535 的端口号", "port-status-neutral");
            return;
        }
        if (portAvailabilityService.isAvailable(port)) {
            setPortStatus(label, "端口 " + port + " 当前可用", "port-status-available");
        } else {
            setPortStatus(label, "端口 " + port + " 已被本机占用", "port-status-occupied");
        }
    }

    private static Integer validPortOrNull(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort == null ? "" : rawPort.strip());
            return port >= 1 && port <= 65_535 ? port : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void setPortStatus(Label label, String text, String styleClass) {
        label.setText(text);
        label.getStyleClass().removeAll("port-status-available", "port-status-occupied", "port-status-neutral");
        label.getStyleClass().add(styleClass);
    }

    private String displayBackendPort() {
        String value = backendPortField.getText();
        return value == null || value.isBlank() ? "待确认" : value.strip();
    }

    private String previewDescription(JobDraft draft, DeploymentSettings settings) {
        String base = draft.jobName() + "  ·  仅监听 " + draft.watchedPath();
        if ("frontend".equals(draft.kind())) {
            String port = settings.frontendDeploymentMode() == FrontendDeploymentMode.IIS
                    ? "  ·  端口 " + settings.frontendPort()
                    : "";
            return base + "  ·  " + settings.frontendDeploymentMode() + port;
        }
        return base + "  ·  端口 " + settings.backendPort()
                + "  ·  服务 " + settings.backendServiceId();
    }

    private void invalidateJobPreview() {
        if (jobPreviewList == null) {
            return;
        }
        jobPreviewList.getItems().clear();
        confirmationCheckBox.setSelected(false);
        applyButton.setDisable(true);
        if (deploymentProgressPane != null) {
            setPaneVisible(deploymentProgressPane, false);
        }
    }

    private ProjectProfile requireProject() {
        if (projectProfile == null) {
            throw new IllegalStateException("请先选择项目目录。");
        }
        return projectProfile;
    }

    private boolean hasConnectionSettings() {
        return config != null
                && config.get("JENKINS_URL").isPresent()
                && config.get("JENKINS_USERNAME").isPresent()
                && config.get("JENKINS_PASSWORD").isPresent();
    }

    private void scheduleGerritLookup(String projectName) {
        gerritLookupVersion++;
        resolvedGerritProjectName = "";
        repositoryUrlField.clear();
        updateAutoDeploymentSummary();
        gerritLookupDelay.stop();
        if (projectName == null || projectName.isBlank()) {
            gerritProjectStatusLabel.setText("输入项目名后自动查询固定 Gerrit");
            return;
        }
        gerritProjectStatusLabel.setText("等待自动识别…");
        gerritLookupDelay.playFromStart();
    }

    private void resolveGerritProject() {
        String requestedName = gerritProjectField.getText().strip();
        long requestVersion = gerritLookupVersion;
        if (requestedName.isBlank()) {
            return;
        }
        gerritProjectStatusLabel.setText("正在从 Gerrit 查询…");
        executor.submit(() -> {
            try {
                var project = gerritClient.resolveProject(requestedName);
                Platform.runLater(() -> {
                    if (requestVersion != gerritLookupVersion) {
                        return;
                    }
                    resolvedGerritProjectName = project.name();
                    repositoryUrlField.setText(project.repositoryUrl());
                    gerritProjectStatusLabel.setText("已识别：" + project.name());
                    updateAutoDeploymentSummary();
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    if (requestVersion != gerritLookupVersion) {
                        return;
                    }
                    resolvedGerritProjectName = "";
                    repositoryUrlField.clear();
                    gerritProjectStatusLabel.setText(Optional.ofNullable(exception.getMessage()).orElse("识别失败"));
                    updateAutoDeploymentSummary();
                });
            }
        });
    }

    private void requireResolvedGerritProject() {
        if (resolvedGerritProjectName.isBlank() || repositoryUrlField.getText().isBlank()) {
            throw new IllegalStateException("请先输入有效的 Gerrit 项目名并等待自动识别完成。");
        }
    }

    private String displayRelative(Path path) {
        if (projectProfile == null || projectProfile.root().equals(path)) {
            return "项目根目录";
        }
        return projectProfile.root().relativize(path).toString();
    }

    private String projectSummary() {
        List<String> parts = new ArrayList<>();
        parts.add("项目：" + projectProfile.projectName());
        projectProfile.frontendDirectory().ifPresent(path -> parts.add("前端：" + displayRelative(path)));
        projectProfile.backendDirectory().ifPresent(path -> parts.add("后端：" + displayRelative(path)));
        return String.join("  ·  ", parts);
    }

    private void showOnboarding() {
        showStep(WizardState.Step.AGENT, "查看本机 Agent 状态");
    }

    private void showProjectSelection() {
        showStep(WizardState.Step.PROJECT, "本机 Agent 已就绪，请选择项目源码目录");
    }

    private void showProjectSelectionAndRestore() {
        showProjectSelection();
        if (!recentProjectService.hasRememberedDirectory(config)) {
            return;
        }
        Optional<Path> remembered = recentProjectService.existingDirectory(config);
        if (remembered.isEmpty()) {
            clearStaleProjectSelection("上次选择的项目目录已不存在，请重新选择源码根目录");
            return;
        }
        resetProjectSelection();
        try {
            applyProjectSelection(remembered.get(), false);
            footerStatus.setText("已恢复上次项目源码目录：" + remembered.get());
        } catch (Exception exception) {
            clearStaleProjectSelection("上次选择的目录已无法识别为源码项目，请重新选择");
        }
    }

    private void clearStaleProjectSelection(String message) {
        resetProjectSelection();
        projectPathField.clear();
        frontendResultLabel.setText("待识别");
        backendResultLabel.setText("待识别");
        try {
            recentProjectService.clear(config);
        } catch (IOException ignored) {
            // 即使配置暂时无法写入，也保持第二步可用并让用户重新选择。
        }
        showProjectSelection();
        footerStatus.setText(message);
    }

    private void showDeployment() {
        showStep(WizardState.Step.DEPLOYMENT, "项目识别完成，请确认生产参数");
    }

    private void showStep(WizardState.Step step, String statusText) {
        if (!wizardState.open(step)) {
            return;
        }
        dashboardVisible = false;
        setPaneVisible(dashboardPane, false);
        setPaneVisible(onboardingPane, step == WizardState.Step.AGENT);
        setPaneVisible(projectPane, step == WizardState.Step.PROJECT);
        setPaneVisible(deploymentPane, step == WizardState.Step.DEPLOYMENT);
        updateNavigation();
        contentScrollPane.setVvalue(0);
        footerStatus.setText(statusText);
    }

    private void updateNavigation() {
        navDashboardButton.getStyleClass().remove("nav-item-active");
        if (dashboardVisible) {
            navDashboardButton.getStyleClass().add("nav-item-active");
        }
        updateNavigationButton(navStepOneButton, WizardState.Step.AGENT);
        updateNavigationButton(navStepTwoButton, WizardState.Step.PROJECT);
        updateNavigationButton(navStepThreeButton, WizardState.Step.DEPLOYMENT);
    }

    private void updateNavigationButton(Button button, WizardState.Step step) {
        button.getStyleClass().removeAll("nav-item-active", "nav-item-complete");
        if (!dashboardVisible && wizardState.currentStep() == step) {
            button.getStyleClass().add("nav-item-active");
        } else if (wizardState.isComplete(step)) {
            button.getStyleClass().add("nav-item-complete");
        }
        button.setDisable(!wizardState.canOpen(step));
    }

    private static void setPaneVisible(VBox pane, boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    private boolean deploymentBusy;

    private void setBusy(boolean busy) {
        deploymentBusy = busy;
        boolean existingAgent = config != null && AppPaths.isExternalAgent(config);
        provisionButton.setDisable(busy || existingAgent || installDriveComboBox.getValue() == null);
        installDriveComboBox.setDisable(busy || existingAgent);
        retryButton.setDisable(busy);
        previewButton.setDisable(busy);
        applyButton.setDisable(busy || !confirmationCheckBox.isSelected() || jobPreviewList.getItems().isEmpty());
        if (busy) {
            footerStatus.setText("正在处理，请稍候…");
        }
    }

    private <T> void runAsync(CheckedSupplier<T> supplier, java.util.function.Consumer<T> onSuccess) {
        executor.submit(() -> {
            try {
                T result = supplier.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    setBusy(false);
                    if (deploymentProgressPane != null && deploymentProgressPane.isVisible()) {
                        deploymentProgressBar.setProgress(0);
                        deploymentProgressLabel.setText("自动部署未完成，请查看错误并导出诊断");
                    }
                    retryButton.setVisible(true);
                    retryButton.setManaged(true);
                    showError("操作未完成", exception);
                });
            }
        });
    }

    private void showError(String title, Throwable exception) {
        String message = Optional.ofNullable(exception.getMessage()).filter(value -> !value.isBlank())
                .orElse("未知错误");
        lastError = diagnosticsService.describe(exception, config == null ? new EnvConfig() : config);
        currentStepTitle.setText(title);
        currentStepDetail.setText(message);
        footerStatus.setText(title + "：" + message);
        showBrandedErrorDialog(title, message, exception);
    }

    private void showBrandedErrorDialog(String title, String message, Throwable exception) {
        JenkinsException jenkinsFailure = findJenkinsFailure(exception);
        boolean canOpenConnectionSettings = jenkinsFailure != null
                && (jenkinsFailure.statusCode() == 401 || jenkinsFailure.statusCode() == 403);

        ButtonType dismissButton = new ButtonType("知道了", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType diagnosticsButton = new ButtonType("导出诊断", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        ButtonType settingsButton = new ButtonType("连接设置", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Jenkins一键部署助手");
        dialog.initModality(Modality.WINDOW_MODAL);
        if (provisionButton.getScene() != null) {
            dialog.initOwner(provisionButton.getScene().getWindow());
        }
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().setGraphic(null);
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/ui/app.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("assistant-dialog");
        if (canOpenConnectionSettings) {
            dialog.getDialogPane().getButtonTypes().add(settingsButton);
        }
        dialog.getDialogPane().getButtonTypes().add(diagnosticsButton);
        dialog.getDialogPane().getButtonTypes().add(dismissButton);

        Label kicker = new Label("需要处理");
        kicker.getStyleClass().add("dialog-kicker");
        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label detail = new Label(message);
        detail.setWrapText(true);
        detail.getStyleClass().add("dialog-message");
        Label nextStep = new Label(dialogHelpText(jenkinsFailure));
        nextStep.setWrapText(true);
        nextStep.getStyleClass().add("dialog-help");
        VBox content = new VBox(10, kicker, heading, detail, nextStep);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(510);

        Button dismiss = (Button) dialog.getDialogPane().lookupButton(dismissButton);
        dismiss.getStyleClass().add("button-primary");
        Button diagnostics = (Button) dialog.getDialogPane().lookupButton(diagnosticsButton);
        diagnostics.getStyleClass().add("button-secondary");
        if (canOpenConnectionSettings) {
            Button settings = (Button) dialog.getDialogPane().lookupButton(settingsButton);
            settings.getStyleClass().add("button-secondary");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.filter(diagnosticsButton::equals).isPresent()) {
            handleExportDiagnostics();
        } else if (result.filter(settingsButton::equals).isPresent()) {
            handleConnectionSettings();
        }
    }

    private static String dialogHelpText(JenkinsException failure) {
        if (failure == null) {
            return "可以先重试；如果仍然失败，请导出诊断文件交给管理员。";
        }
        return switch (failure.statusCode()) {
            case 401 -> "下一步：打开连接设置，重新保存 Jenkins 账号和密码后再试。";
            case 403 -> "下一步：先点“重试检测”。如果持续出现，请让管理员检查节点查看与创建权限。";
            default -> "可以先重试；如果仍然失败，请导出诊断文件交给管理员。";
        };
    }

    private static JenkinsException findJenkinsFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof JenkinsException failure) {
                return failure;
            }
        }
        return null;
    }

    private static void addField(GridPane grid, int row, String label, javafx.scene.control.Control field) {
        Label caption = new Label(label);
        caption.getStyleClass().add("form-label");
        field.setPrefWidth(420);
        grid.add(caption, 0, row);
        grid.add(field, 1, row);
    }

    private static String formatBytes(long value) {
        double gibibytes = value / 1024d / 1024d / 1024d;
        return String.format("%.1f GiB", gibibytes);
    }

    private void renderStorageVolumes() {
        diskListPane.getChildren().clear();
        installDriveComboBox.getItems().setAll(host.storageVolumes());
        for (StorageVolume volume : host.storageVolumes()) {
            Label chip = new Label(volumeChoiceText(volume));
            chip.getStyleClass().add("storage-chip");
            diskListPane.getChildren().add(chip);
        }

        config.get("AGENT_INSTALL_DIR").ifPresent(configuredValue -> {
            Path configuredPath = Path.of(configuredValue).toAbsolutePath().normalize();
            host.storageVolumes().stream()
                    .filter(volume -> configuredPath.equals(AppPaths.agentDirectoryForRoot(volume.root())))
                    .findFirst()
                    .ifPresent(installDriveComboBox::setValue);
        });
        if (installDriveComboBox.getValue() == null) {
            installPathLabel.setText("尚未选择，不会默认安装到 C 盘");
            provisionButton.setDisable(true);
        }
    }

    private static String volumeChoiceText(StorageVolume volume) {
        return volume.root() + "  " + formatBytes(volume.freeBytes()) + " 可用 / "
                + formatBytes(volume.totalBytes()) + " 总计";
    }

    private record ConnectionCheck(long responseMillis, NodeStatus nodeStatus) {
    }

    private record DeploymentCompletion(DeploymentRunResult deployment, boolean redisConfigured) {
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
