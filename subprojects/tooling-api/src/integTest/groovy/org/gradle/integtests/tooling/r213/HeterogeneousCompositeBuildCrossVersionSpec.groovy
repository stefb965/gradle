/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.tooling.r213

import org.gradle.integtests.fixtures.executer.GradleDistribution
import org.gradle.integtests.fixtures.versions.ReleasedVersionDistributions
import org.gradle.integtests.tooling.fixture.CompositeToolingApiSpecification
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.r16.CustomModel
import org.gradle.test.fixtures.file.TestFile
import org.gradle.tooling.connection.*
import org.gradle.tooling.internal.protocol.DefaultBuildIdentity
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.eclipse.EclipseProject
import org.gradle.tooling.model.gradle.ProjectPublications
import org.gradle.util.CollectionUtils
import spock.lang.Ignore

/**
 * Tests composites with a different Gradle versions.
 * This test creates a composite combining a project for a fixed Gradle version (2.8) with the target gradle version for the test.
 */
class HeterogeneousCompositeBuildCrossVersionSpec extends CompositeToolingApiSpecification {

    private final static GradleDistribution GRADLE_2_8 = new ReleasedVersionDistributions().getDistribution("2.8")

    TestFile varyingBuildRoot
    TestFile fixedBuildRoot

    def setup() {
        varyingBuildRoot = singleProjectBuild("project")
        fixedBuildRoot = singleProjectBuild("project_fixed")
   }

    private GradleConnection openConnection() {
        GradleConnectionBuilder builder = createCompositeBuilder()
        builder.newParticipant(varyingBuildRoot.absoluteFile).useInstallation(targetDist.gradleHomeDir.absoluteFile).create()
        builder.newParticipant(fixedBuildRoot.absoluteFile).useInstallation(GRADLE_2_8.gradleHomeDir.absoluteFile).create()
        return builder.build()
    }

    def "retrieve models for composite with heterogeneous Gradle versions"() {
        when:
        def connection = openConnection()

        def eclipseProjects = connection.getModels(EclipseProject)
        def buildEnvironments = connection.getModels(BuildEnvironment)

        then:
        eclipseProjects.size() == 2
        buildEnvironments.size() == 2

        cleanup:
        connection?.close()
    }

    @TargetGradleVersion(">=1.0 <1.12")
    def "gets errors for unsupported models for composite with heterogeneous Gradle versions"() {
        when:
        def connection = openConnection()
        def modelResults = connection.getModels(ProjectPublications)

        then:
        modelResults.size() == 2
        def varyingResult = findFailureResult(modelResults, varyingBuildRoot)
        assertFailure(varyingResult.failure, "The version of Gradle you are using (${targetDistVersion.version}) does not support building a model of type 'ProjectPublications'. Support for building 'ProjectPublications' models was added in Gradle 1.12 and is available in all later versions.")

        def fixedResult = modelResults.find {it != varyingResult}
        fixedResult.failure == null
        fixedResult.model != null

        cleanup:
        connection?.close()
    }

    @TargetGradleVersion(">=1.0 <1.6")
    def "gets errors for unknown models for composite with heterogeneous Gradle versions"() {
        when:
        def connection = openConnection()
        def modelResults = connection.getModels(CustomModel)

        then:
        modelResults.size() == 2
        def varyingResult = findFailureResult(modelResults, varyingBuildRoot)
        assertFailure(varyingResult.failure, "The version of Gradle you are using (${targetDistVersion.version}) does not support building a model of type 'CustomModel'. Support for building custom tooling models was added in Gradle 1.6 and is available in all later versions.")

        def fixedResult = findFailureResult(modelResults, fixedBuildRoot)
        assertFailure(fixedResult.failure, "No model of type 'CustomModel' is available in this build.")

        cleanup:
        connection?.close()
    }

    @Ignore("Need to support custom types from the client")
    @TargetGradleVersion(">=1.6")
    def "can retrieve custom models from some participants"() {
        varyingProject.file("build.gradle") <<
                """
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.tooling.provider.model.ToolingModelBuilder
import javax.inject.Inject

apply plugin: CustomPlugin

class CustomModel implements Serializable {
    String getValue() { 'greetings' }
    Set<CustomThing> getThings() { return [new CustomThing()] }
    Map<String, CustomThing> getThingsByName() { return [thing: new CustomThing()] }
}
class CustomThing implements Serializable {
}
class CustomBuilder implements ToolingModelBuilder {
    boolean canBuild(String modelName) {
        return modelName == '${CustomModel.name}'
    }
    Object buildAll(String modelName, Project project) {
        return new CustomModel()
    }
}
class CustomPlugin implements Plugin<Project> {
    @Inject
    CustomPlugin(ToolingModelBuilderRegistry registry) {
        registry.register(new CustomBuilder())
    }

    public void apply(Project project) {
    }
}
"""
        GradleConnection connection = builder.build()
        when:
        def modelResults = connection.getModels(CustomModel)

        then:
        modelResults.size() == 2
        def varyingResult = findModelResult(modelResults, varyingBuildIdentity)
        varyingResult.failure == null
        varyingResult.model.value == 'greetings'
        varyingResult.model.things.find { it instanceof CustomModel.Thing }
        varyingResult.model.thingsByName.thing instanceof CustomModel.Thing

        def fixedResult = findModelResult(modelResults, fixedBuildIdentity)
        assertFailure(fixedResult.failure, "No model of type 'CustomModel' is available in this build.")

    }

    ModelResult findFailureResult(Iterable<ModelResult> modelResults, File rootDir) {
        BuildIdentity buildIdentity = new DefaultBuildIdentity(rootDir)
        def results = modelResults.findAll { it instanceof FailureModelResult && buildIdentity.equals(it.buildIdentity)}
        return CollectionUtils.single(results)
    }

    ModelResult findModelResult(ModelResults modelResults, BuildIdentity buildIdentity) {
        CollectionUtils.single(modelResults.findAll { ModelResult modelResult ->
            modelResult.model.identifier.build.equals(buildIdentity)
        })
    }
}
