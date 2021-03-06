/*
 * Copyright 2015 Intershop Communications AG.
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
 *  limitations under the License.
 */
package com.intershop.gradle.versionrecommender.recommendation

import com.intershop.gradle.test.builder.TestIvyRepoBuilder
import com.intershop.gradle.test.util.TestDir
import com.intershop.gradle.versionrecommender.update.UpdateConfiguration
import com.intershop.gradle.versionrecommender.update.UpdateConfigurationItem
import com.intershop.gradle.versionrecommender.util.UpdatePos
import com.intershop.gradle.versionrecommender.util.VersionExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TestName
import spock.lang.Specification

class IvyProviderSpec extends Specification {

    /**
     * Project directory for tests
     */
    @TestDir
    File testProjectDir

    /**
     * Test name
     */
    @Rule
    TestName testName = new TestName()

    /**
     * Canonical name of the test name
     */
    protected String canonicalName

    /**
     * Test project
     */
    protected Project project

    def setup() {
        canonicalName = testName.getMethodName().replaceAll(' ', '-')
        project = ProjectBuilder.builder().withName(canonicalName).withProjectDir(testProjectDir).build()
        project.repositories.add(project.repositories.jcenter())
    }

    def 'Ivy provider spec'() {
        setup:
        ClassLoader classLoader = getClass().getClassLoader()
        File file = new File(classLoader.getResource('ivytest/ivy.xml').getFile())

        when:
        IvyRecommendationProvider provider = new IvyRecommendationProvider('test', project, file)
        provider.initializeVersion()

        then:
        provider.getVersion('javax.inject','javax.inject') == '1'
    }

    def 'Ivy provider with dependencies'() {
        setup:
        ClassLoader classLoader = getClass().getClassLoader()
        File file = new File(classLoader.getResource('ivytest/ivy.xml').getFile())

        when:
        IvyRecommendationProvider provider = new IvyRecommendationProvider('test', project, file)
        provider.transitive = true
        provider.initializeVersion()

        then:
        provider.getVersion('aopalliance', 'aopalliance') == '1.0'
    }

    def 'Ivy provider with dependency configuration'() {
        setup:
        File repoDir = new File(testProjectDir, 'repo')
        String ivyPattern = '[organisation]/[module]/[revision]/[type]s/ivy-[revision].xml'
        String artifactPattern = '[organisation]/[module]/[revision]/[ext]s/[artifact]-[type](-[classifier])-[revision].[ext]'

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '2.0.0') {
                dependency org: 'com.intershop', name: 'component1', rev: '1.0.0'
                dependency org: 'com.intershop', name: 'component2', rev: '2.0.0'
            }
        }.writeTo(repoDir)

        project.repositories.ivy {
            name 'ivyLocal'
            url "file://${repoDir.absolutePath}"
            layout ('pattern') {
                ivy ivyPattern
                artifact artifactPattern
                artifact ivyPattern
            }
        }

        when:
        IvyRecommendationProvider provider = new IvyRecommendationProvider('test', project, 'com.intershop:filter:2.0.0')
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '1.0.0'
    }

    def 'Ivy provider with local dependency configuration'() {
        setup:
        File repoDir = new File(testProjectDir, 'repo')
        File localRepoDir = new File(testProjectDir, 'localrepo')
        String ivyPattern = '[organisation]/[module]/[revision]/[type]s/ivy-[revision].xml'
        String artifactPattern = '[organisation]/[module]/[revision]/[ext]s/[artifact]-[type](-[classifier])-[revision].[ext]'

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '2.0.0') {
                dependency org: 'com.intershop', name: 'component1', rev: '1.0.0'
                dependency org: 'com.intershop', name: 'component2', rev: '2.0.0'
            }
        }.writeTo(repoDir)

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '2.0.0-LOCAL') {
                dependency org: 'com.intershop', name: 'component1', rev: '1.0.0'
                dependency org: 'com.intershop', name: 'component2', rev: '2.0.0-LOCAL'
            }
        }.writeTo(localRepoDir)

        project.repositories {
            ivy {
                name 'ivyLocal'
                url "file://${repoDir.absolutePath}"
                layout ('pattern') {
                    ivy ivyPattern
                    artifact artifactPattern
                    artifact ivyPattern
                }
            }
            ivy {
                name 'ivyLocalLocal'
                url "file://${localRepoDir.absolutePath}"
                layout('pattern') {
                    ivy ivyPattern
                    artifact artifactPattern
                    artifact ivyPattern
                }
            }
        }

        when:
        IvyRecommendationProvider provider = new IvyRecommendationProvider('test', project, 'com.intershop:filter:2.0.0')
        provider.setVersionExtension(VersionExtension.LOCAL)
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '1.0.0'
        provider.getVersion('com.intershop', 'component2') == '2.0.0-LOCAL'

        when:
        provider.setVersionExtension(VersionExtension.NONE)
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '1.0.0'
        provider.getVersion('com.intershop', 'component2') == '2.0.0'
    }

    def 'Ivy provider with updated version from local repo'() {
        setup:
        File repoDir = new File(testProjectDir, 'repo')
        String ivyPattern = '[organisation]/[module]/[revision]/[type]s/ivy-[revision].xml'
        String artifactPattern = '[organisation]/[module]/[revision]/[ext]s/[artifact]-[type](-[classifier])-[revision].[ext]'

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '1.0.0') {
                dependency org: 'com.intershop', name: 'component1', rev: '1.0.0'
                dependency org: 'com.intershop', name: 'component2', rev: '1.0.0'
            }
        }.writeTo(repoDir)

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '1.0.1') {
                dependency org: 'com.intershop', name: 'component1', rev: '1.0.1'
                dependency org: 'com.intershop', name: 'component2', rev: '1.0.1'
            }
        }.writeTo(repoDir)

        new TestIvyRepoBuilder().repository( ivyPattern: ivyPattern, artifactPattern: artifactPattern ) {
            module(org: 'com.intershop', name:'filter', rev: '2.0.0') {
                dependency org: 'com.intershop', name: 'component1', rev: '2.0.0'
                dependency org: 'com.intershop', name: 'component2', rev: '2.0.0'
            }
        }.writeTo(repoDir)

        project.repositories {
            ivy {
                name 'ivyLocal'
                url "file://${repoDir.absolutePath}"
                layout('pattern') {
                    ivy ivyPattern
                    artifact artifactPattern
                    artifact ivyPattern
                }
            }
        }

        when:
        IvyRecommendationProvider provider = new IvyRecommendationProvider('test', project, 'com.intershop:filter:1.0.0')
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '1.0.0'

        when:
        UpdateConfiguration uc = new UpdateConfiguration(project)
        uc.ivyPattern = ivyPattern

        UpdateConfigurationItem uci = new UpdateConfigurationItem('filter', 'com.intershop', 'filter')
        uc.addConfigurationItem(uci)
        provider.update(uc)
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '1.0.1'

        when:
        uci.update = UpdatePos.MAJOR.toString()
        provider.update(uc)
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '2.0.0'

        when:
        provider.store(provider.getVersionFile())
        provider.initializeVersion()

        then:
        provider.getVersion('com.intershop', 'component1') == '2.0.0'
    }
}
