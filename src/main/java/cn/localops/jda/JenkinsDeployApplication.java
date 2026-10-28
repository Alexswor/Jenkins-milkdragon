package cn.localops.jda;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;

public final class JenkinsDeployApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(JenkinsDeployApplication.class.getResource("/ui/main-view.fxml"));
        Scene scene = new Scene(loader.load(), 1180, 760);
        scene.getStylesheets().add(JenkinsDeployApplication.class.getResource("/ui/app.css").toExternalForm());
        stage.setTitle("Jenkins一键部署助手");
        stage.getIcons().add(new Image(
                JenkinsDeployApplication.class.getResource("/icons/app-icon.png").toExternalForm()));
        stage.setMinWidth(1040);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
