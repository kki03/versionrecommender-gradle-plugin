package com.intershop.gradle.versionrecommender.tasks

import com.intershop.gradle.versionrecommender.extension.RecommendationProvider
import com.intershop.gradle.versionrecommender.extension.VersionRecommenderExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

class UpdateVersion extends DefaultTask {

    RecommendationProvider provider

    @TaskAction
    void runUpdate() {
        if(provider.isVersionRequired() && ! (provider.getVersionFromProperty())) {
            throw new GradleException("It is necessary to specify a version property with -P${provider.getVersionPropertyName()} = <version>.")
        }
        VersionRecommenderExtension extension = project.project.extensions.findByType(VersionRecommenderExtension)
        println extension.updateConfiguration.ivyPattern
    }
}
