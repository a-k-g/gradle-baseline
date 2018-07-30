/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.baseline.plugins;

import com.google.common.base.Splitter;
import com.palantir.gradle.circlestyle.CheckstyleReportHandler;
import com.palantir.gradle.circlestyle.CircleBuildFailureListener;
import com.palantir.gradle.circlestyle.CircleBuildFinishedAction;
import com.palantir.gradle.circlestyle.CircleStyleFinalizer;
import com.palantir.gradle.circlestyle.JavacFailuresSupplier;
import com.palantir.gradle.circlestyle.StyleTaskTimer;
import com.palantir.gradle.circlestyle.XmlReportFailuresSupplier;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.profile.ProfileListener;
import org.gradle.profile.ProfileReportRenderer;

public final class BaselineCircleCi extends AbstractBaselinePlugin {
    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
    private static final FileAttribute<Set<PosixFilePermission>> PERMS_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"));

    @Override
    public void apply(Project rootProject) {
        final String circleReportsDir = System.getenv("CIRCLE_TEST_REPORTS");
        if (circleReportsDir == null) {
            return;
        }
        String circleArtifactsDir = System.getenv("CIRCLE_ARTIFACTS");
        if (circleArtifactsDir == null) {
            return;
        }

        try {
            Files.createDirectories(Paths.get(circleReportsDir), PERMS_ATTRIBUTE);
            Files.createDirectories(Paths.get(circleArtifactsDir), PERMS_ATTRIBUTE);
        } catch (IOException e) {
            throw new RuntimeException("failed to create CIRCLE_ARTIFACTS and CIRCLE_TEST_REPORTS directory", e);
        }

        configureBuildFailureFinalizer(rootProject, circleReportsDir);

        StyleTaskTimer timer = new StyleTaskTimer();
        rootProject.getGradle().addListener(timer);

        project.getRootProject().allprojects(proj -> {
            proj.getTasks().withType(Test.class, test -> {
                Path junitArtifactsDir = Paths.get(circleArtifactsDir, "junit");
                Path junitReportsDir = Paths.get(circleReportsDir, "junit");
                for (String component : Splitter.on(":").split(test.getPath().substring(1))) {
                    junitArtifactsDir = junitArtifactsDir.resolve(component);
                    junitReportsDir = junitReportsDir.resolve(component);
                }
                test.getReports().getHtml().setEnabled(true);
                test.getReports().getHtml().setDestination(junitArtifactsDir.toFile());
                test.getReports().getJunitXml().setEnabled(true);
                test.getReports().getJunitXml().setDestination(junitReportsDir.toFile());
            });
            proj.getTasks().withType(Checkstyle.class, checkstyle ->
                    CircleStyleFinalizer.registerFinalizer(
                            checkstyle,
                            timer,
                            XmlReportFailuresSupplier.create(checkstyle, new CheckstyleReportHandler()),
                            Paths.get(circleReportsDir, "checkstyle").toFile()));
            proj.getTasks().withType(JavaCompile.class, javac ->
                    CircleStyleFinalizer.registerFinalizer(
                            javac,
                            timer,
                            JavacFailuresSupplier.create(javac),
                            new File(circleReportsDir, "javac")));
        });

        if (project.getGradle().getStartParameter().isProfile()) {
            project.getGradle().addListener((ProfileListener) buildProfile -> {
                ProfileReportRenderer renderer = new ProfileReportRenderer();
                File file = Paths.get(circleArtifactsDir, "profile", "profile-"
                        + fileDateFormat.format(new Date(buildProfile.getBuildStarted())) + ".html").toFile();
                renderer.writeTo(buildProfile, file);
            });
        }
    }

    private static void configureBuildFailureFinalizer(Project rootProject, String circleReportsDir) {
        int attemptNumber = 1;
        File targetFile = new File(new File(circleReportsDir, "gradle"), "build.xml");
        while (targetFile.exists()) {
            targetFile = new File(new File(circleReportsDir, "gradle"), "build" + (++attemptNumber) + ".xml");
        }
        Integer container;
        try {
            container = Integer.parseInt(System.getenv("CIRCLE_NODE_INDEX"));
        } catch (NumberFormatException e) {
            container = null;
        }
        CircleBuildFailureListener listener = new CircleBuildFailureListener();
        CircleBuildFinishedAction action = new CircleBuildFinishedAction(container, targetFile, listener);
        rootProject.getGradle().addListener(listener);
        rootProject.getGradle().buildFinished(action);
    }

}
