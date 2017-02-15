package com.intershop.gradle.versionrecommender

import com.intershop.gradle.versionrecommender.extension.VersionRecommenderExtension
import com.intershop.gradle.versionrecommender.util.NoVersionException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyResolveDetails

class VersionRecommenderPlugin implements Plugin<Project> {

    private VersionRecommenderExtension extension

    @Override
    void apply(final Project project) {
        applyToRootProject(project.getRootProject())
    }

    private void applyToRootProject(Project project) {
        project.logger.info('Create extension {} for {}', VersionRecommenderExtension.EXTENSIONNAME, project.name)

        extension = project.extensions.findByType(VersionRecommenderExtension) ?: project.extensions.create(VersionRecommenderExtension.EXTENSIONNAME, VersionRecommenderExtension, project)

        applyRecommendation(project)
        project.getSubprojects().each {
            applyRecommendation(it)
        }
    }

    private void applyRecommendation(Project project) {
        project.getConfigurations().all { Configuration conf ->
            conf.getResolutionStrategy().eachDependency { DependencyResolveDetails details ->
                if(! details.requested.version || extension.forceRecommenderVersion) {
                    String rv = extension.provider.getVersion(details.requested.group, details.requested.name)

                    if(details.requested.version && !(rv))
                        rv = details.requested.version

                    if(rv) {
                        details.useVersion(rv)
                    } else {
                        throw new NoVersionException("Version for '${details.requested.group}:${details.requested.name}' not found! Please check your dependency configuration and the version recommender version.")
                    }
                }
            }
        }
    }

}