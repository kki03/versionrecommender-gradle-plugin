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
package com.intershop.gradle.versionrecommender.update

import com.intershop.gradle.versionrecommender.util.UpdatePos
import com.intershop.release.version.ParserException
import com.intershop.release.version.Version
import com.intershop.release.version.VersionParser
import com.intershop.release.version.VersionType
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

/**
 * This class provides data and methods for the
 * version update.
 */
@CompileStatic
@Slf4j
class VersionUpdater {

    // version regex for a semantic version
    private static final String versionregex = '(\\d+)(\\.\\d+)(\\.\\d+)(\\.\\d+)?'

    // the target project
    private Project project

    // repository lists for maven and ivy repos, file and remote based
    private List<ArtifactRepository> mvnHttpRepList
    private List<ArtifactRepository> mvnFileRepList
    private List<ArtifactRepository> ivyHttpRepList
    private List<ArtifactRepository> ivyFileRepList

    /**
     * Ivy pattern for Ivy repository access
     */
    public String ivyPattern

    /**
     * Version log file
     */
    public File updateLogFile

    /**
     * Constructor
     *
     * @param project   the target project
     */
    VersionUpdater(Project project) {
        this.project = project
    }

    /**
     * Initialize repository lists for the first call of the class, after evaluation of the project.
     */
    private void initLists() {
        if(mvnHttpRepList == null || mvnFileRepList == null) {
            List<ArtifactRepository> mvnRepList = project.getRepositories().findAll { it instanceof MavenArtifactRepository }
            mvnHttpRepList = mvnRepList.findAll { ((MavenArtifactRepository) it).url.scheme.startsWith('http') }
            mvnFileRepList = mvnRepList.findAll { ((MavenArtifactRepository) it).url.scheme.startsWith('file') }
        }
        if(ivyHttpRepList == null || ivyFileRepList == null) {
            List<ArtifactRepository> ivyRepList = project.getRepositories().findAll { it instanceof IvyArtifactRepository }
            ivyHttpRepList = ivyRepList.findAll { ((IvyArtifactRepository) it).url.scheme.startsWith('http') }
            ivyFileRepList = ivyRepList.findAll { ((IvyArtifactRepository) it).url.scheme.startsWith('file') }
        }
    }

    /**
     * <p>Calculate a list of versions for the specified module from.
     * This is the order for the repositories:</p>
     * <ul>
     *     <li>Maven repositories remote based (http(s))</li>
     *     <li>Ivy repositories remote based (http(s))</li>
     *     <li>Ivy repositories file based</li>
     *     <li>Maven repositories file based</li>
     * </ul>
     *
     * @param group     Module group or organization
     * @param name      Module name or artifact id
     * @return          List of available versions
     */
    List<String> getVersionList(String group, String name) {
        List<String> versionList = []
        initLists()

        mvnHttpRepList.any { ArtifactRepository repo ->
            versionList = HTTPVersionProvider.getVersionFromMavenMetadata( ((MavenArtifactRepository)repo).getUrl().toString(), group, name,
                    ((MavenArtifactRepository)repo).credentials.username, ((MavenArtifactRepository)repo).credentials.password)
            if(versionList)
                return true
        }
        if(! versionList) {
            if(ivyPattern) {
                ivyHttpRepList.any { ArtifactRepository repo ->
                    versionList = HTTPVersionProvider.getVersionsFromIvyListing( ((IvyArtifactRepository)repo).getUrl().toString(), ivyPattern, group, name,
                            ((IvyArtifactRepository)repo).credentials.username, ((IvyArtifactRepository)repo).credentials.password)
                    if (versionList)
                        return true
                }
                if (!versionList) {
                    ivyFileRepList.any { ArtifactRepository repo ->
                        versionList = FileVersionProvider.getVersionsFromIvyListing(new File(((IvyArtifactRepository)repo).url), ivyPattern, group, name)
                        if (versionList)
                            return true
                    }
                }
            }

            if(! versionList) {
                mvnFileRepList.any { ArtifactRepository repo ->
                    versionList = FileVersionProvider.getVersionFromMavenMetadata(new File(((MavenArtifactRepository)repo).url), group, name)
                    if(versionList)
                        return true
                }
            }
        }

        return versionList
    }

    /**
     * This method calculates the update version for semantic
     * versions with three or four digits. A patch or minor
     * version digit with the value 0 can be empty (Apache style).
     *
     * @see <a href="https://github.com/IntershopCommunicationsAG/extended-version">Library extended-version</a>
     *
     * @param group     Module group or organization
     * @param name      Module name or artifact id
     * @param version   Configured version
     * @param pos       Update position. Default is HOTFIX and for versions with three digits PATCH.
     * @return          update version or if not available null
     */
    String getUpdateVersion(String group, String name, String version, UpdatePos pos = UpdatePos.HOTFIX) {
        List<String> versionList = getVersionList(group, name)
        if(versionList) {
            String uv = calculateUpdateVersion(filterVersion(versionList, version.trim(), pos), version)
            createVersionLog(group, name, version, uv, versionList)
            if(uv) {
                return uv
            }
        } else {
            createWOList(group, name, version)
        }
        return null
    }

    /**
     * <p>This method calculates the update version for semantic
     * versions with three or four digits and a special extension, eg 3.5.GA.
     * This extension can be configured with an pattern. If the current
     * configured version is different, it is possible to configure a
     * special version pattern. The matching extension will removed for
     * comparison.</p>
     * <p>A patch or minor version digit with the value 0 can be empty (Apache style).</p>
     *
     * @param group             Module group or organization
     * @param name              Module name or artifact id
     * @param version           Configured version
     * @param searchPattern     Regex pattern for an extension
     * @param pos               Update position. Default is HOTFIX and for versions with three digits PATCH.
     * @param versionPattern    Regex pattern for an extension of the configured version if different from searchPattern
     * @return                  update version or if not available null
     */
    String getUpdateVersion(String group, String name, String version, String searchPattern, UpdatePos pos = UpdatePos.HOTFIX, String versionPattern =  searchPattern) {
        List<String> versionList = getVersionList(group, name)
        if(versionList) {
            String uv = calculateUpdateVersion(filterVersion(versionList, version,  searchPattern, pos, versionPattern), version)
            createVersionLog(group, name, version, uv, versionList)
            if(uv && versionList.contains(uv)) {
                return uv
            }
        } else {
            createWOList(group, name, version)
        }
        return null
    }

    /**
     * <p>This method calculates the update version for non
     * semantic versions. An regex pattern and the position
     * of a group is used for the calculation of the next version.</p>
     *
     * @param group                 Module group or organization
     * @param name                  Module name or artifact id
     * @param version               Configured version
     * @param patternForNextVersion Regex pattern with groups
     * @param sortStringPos         Position of the group - this is used for sorting and filtering
     * @return                      update version or if not available null
     */
    String getUpdateVersion(String group, String name, String version, String patternForNextVersion, int sortStringPos) {
        List<String> versionList = getVersionList(group, name)
        if(versionList) {
            String uv = calculateUpdateVersion(filterVersion(versionList, version, patternForNextVersion, sortStringPos), version)
            createVersionLog(group, name, version, uv, versionList)
            if(uv && versionList.contains(uv)) {
                return uv
            }
        } else {
            createWOList(group, name, version)
        }
        return null
    }

    /**
     * Create output for existing version list.
     *
     * @param group            Module group or organization
     * @param name             Module name or artifact id
     * @param version          Configured version
     * @param updateVersion    Updated version
     * @param versionList      List wiht versions
     */
    private void createVersionLog(String group, String name, String version, String updateVersion, List<String> versionList) {
        if(versionList) {
            if (updateVersion && versionList.contains(updateVersion)) {
                writeToUpdateLog("${group}:${name} has been updated from ${version} to ${updateVersion}.")
                writeToUpdateLog("${group}:${name} was using ${versionList}")
                writeToUpdateLog("------------------------------------------------------------")
                project.logger.quiet('{}:{} has been updated from {} to {}', group, name, version, updateVersion)
            } else {
                writeToUpdateLog("${group}:${name} was not updated. Version is still ${version}")
                writeToUpdateLog("${group}:${name} was using ${versionList}")
                writeToUpdateLog("------------------------------------------------------------")
                project.logger.quiet('{}:{} was not updated.', group, name)
            }
        }
    }

    /**
     * Create output for not existing versions.
     *
     * @param group            Module group or organization
     * @param name             Module name or artifact id
     * @param version          Configured version
     */
    private void createWOList(String group, String name, String version) {
        writeToUpdateLog("${group}:${name} was not updated. Version is still ${version}")
        writeToUpdateLog("${group}:${name} was without version list.")
        writeToUpdateLog("------------------------------------------------------------")
        project.logger.info('{}:{} was not updated.', group, name)
    }

    /**
     * Calculate the update version from a list of filtered and sorted list of versions.
     * The last item will be used always.
     *
     * @param filteredVersion   List of filtered and sorted version
     * @param version           the configured version
     * @return                  update version or if not available null
     */
    private static String calculateUpdateVersion(List<String> filteredVersion, String version) {
        if(filteredVersion) {
            String updateVersion = filteredVersion.last()
            if(updateVersion != version) {
                return updateVersion
            }
        }
        return null
    }

    /**
     * Filter and sort a list of semantic versions for the specified update position.
     *
     * @param list      List of versions
     * @param version   Configured version
     * @param pos       Update position. Default is HOTFIX and for versions with three digits PATCH.
     * @return          a list of filtered and sorted versions
     */
    protected static List<String> filterVersion(List<String> list, String version, UpdatePos pos = UpdatePos.HOTFIX) {
        int digits = 0
        String staticMetaData = ''

        String[] versionPart = version.split('-')
        def m = (versionPart[0] =~ /\d+/)
        digits = m.count

        if(versionPart.length > 1 && ! (versionPart[1] ==~ /\w+\.?\d+$/)) {
            staticMetaData = versionPart[1]
        }

        try {
            if (digits) {
                Version versionObj = VersionParser.parseVersion(version, digits != 4 ? VersionType.threeDigits : VersionType.fourDigits)
                Version nextVersion = null
                String filter = ''
                String[] versionDigit = versionPart[0].split('\\.')
                switch (pos) {
                    case UpdatePos.MAJOR:
                        nextVersion = versionObj.incrementMajorVersion(staticMetaData)
                        filter = "^\\d+(\\.\\d+)?(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'}"
                        break
                    case UpdatePos.MINOR:
                        nextVersion = versionObj.incrementMinorVersion(staticMetaData)
                        filter = "^${versionDigit[0]}(\\.\\d+)?(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'}"
                        break
                    case UpdatePos.PATCH:
                        nextVersion = versionObj.incrementPatchVersion(staticMetaData)
                        filter = "^${versionDigit[0]}.${versionDigit[1]}(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'}"
                        break
                    case UpdatePos.HOTFIX:
                        nextVersion = digits == 4 ? versionObj.incrementHotfixVersion(staticMetaData) : versionObj.incrementPatchVersion(staticMetaData)
                        filter = "^${versionDigit[0]}.${versionDigit[1]}${digits == 4 ? "\\.${versionDigit[2]}(\\.\\d+)?" : '(\\.\\d+)?'}"
                        break
                }

                if (versionPart.length > 2 || staticMetaData) {
                    filter += "-${staticMetaData}"
                } else {
                    filter += "(-\\w+)?"
                }

                List<Version> filteredList = (List<Version>)(list.findAll {
                    it =~ /${filter}/
                }.collect {
                    try {
                        VersionParser.parseVersion(it, digits != 4 ? VersionType.threeDigits : VersionType.fourDigits)
                    } catch (ParserException ex) {
                        log.info('Version {} can not be parsed as semantic version.', it)
                    }
                }.findAll {Object vo -> ((Version)vo) > versionObj }).sort()

                List<Version> filteredList2 = filteredList.findAll { it >= nextVersion }

                if (filteredList2.isEmpty()) {
                    return filteredList.collect { it.toStringFromOrg() }
                }
                return filteredList2.collect { it.toStringFromOrg() }
            }
        } catch (Exception ex) {
            log.info('Version {} is not a valid semantic version and list {} can not be filtered.', version, list)
        }
        return []
    }

    /**
     * <p>Filter and sort a list of semantic versions for the specified update position
     * with an special extension, eg 3.5.GA.</p>
     * <p>This extension can be configured with an pattern. If the current
     * configured version is different, it is possible to configure a
     * special version pattern. The matching extension will removed for
     * comparison.</p>
     *
     * @param list              List of versions
     * @param version           Configured version
     * @param searchPattern     Regex pattern for an extension
     * @param pos               Update position. Default is HOTFIX and for versions with three digits PATCH.
     * @param versionPattern    Regex pattern for an extension of the configured version if different from searchPattern
     * @return                  a list of filtered and sorted versions
     */
    @CompileDynamic
    protected static List<String> filterVersion(List<String> list, String version, String searchExtPattern, UpdatePos pos = UpdatePos.HOTFIX, String versionExtPattern = searchExtPattern) {
        int digits = 0

        def versionExtension = (version =~ /${versionExtPattern}/)

        if(! versionExtension.count) {
            throw new RuntimeException("Pattern '${versionExtension} for version extension does not match to specified version '${version}'. Please specify a separate pattern for specified version.")
        }
        String semVersion = (version - versionExtension[0])

        def m = (semVersion =~ /\d+/)
        digits = m.count

        if(digits) {
            VersionType type = digits != 4 ? VersionType.threeDigits : VersionType.fourDigits

            Version versionObj = VersionParser.parseVersion(semVersion, type)
            Version nextVersion = null
            String filter = ''
            String[] versionDigit = semVersion.split('\\.')
            switch (pos) {
                case UpdatePos.MAJOR:
                    nextVersion = versionObj.incrementMajorVersion()
                    filter = "^(\\d+(\\.\\d+)?(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'})"
                    break
                case UpdatePos.MINOR:
                    nextVersion = versionObj.incrementMinorVersion()
                    filter = "^(${versionDigit[0]}(\\.\\d+)?(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'})"
                    break
                case UpdatePos.PATCH:
                    nextVersion = versionObj.incrementPatchVersion()
                    filter = "^(${versionDigit[0]}.${versionDigit[1]}(\\.\\d+)?${digits != 4 ? '' : '(\\.\\d+)?'})"
                    break
                case UpdatePos.HOTFIX:
                    nextVersion = digits == 4 ? versionObj.incrementHotfixVersion() : versionObj.incrementPatchVersion()
                    filter = "^(${versionDigit[0]}.${versionDigit[1]}${digits == 4 ? "\\.${versionDigit[2]}(\\.\\d+)?" : '(\\.\\d+)?'})"
                    break
            }
            filter += "${searchExtPattern}"

            Map<Version, String> versionMap = [:]

            List<Version> filteredList = list.findAll{ it =~ /${filter}/ }.each {String vs ->
                def vsm = (vs =~ /${filter}/)
                try {
                    versionMap.put(VersionParser.parseVersion(vsm[0][1].toString(), type), vs)
                }catch (Exception ex) {
                    log.debug('It was not possible to parse version {}', vs)
                }
            }

            List<Version> filteredKeys = versionMap.keySet().findAll {
                it >= versionObj
            } .sort()

            List<Version> filteredKeys2 = versionMap.keySet().findAll {
                it >= nextVersion
            } .sort()

            if(filteredKeys2.isEmpty()) {
                return versionMap.subMap(filteredKeys).values() as List<String>
            } else {
                return versionMap.subMap(filteredKeys2).values() as List<String>
            }
        } else {
            throw new RuntimeException("Version '${version}' ('${semVersion}') is not a semantic version.")
        }
    }

    /**
     * <p>This method filters and sort a list of non
     * semantic versions. An regex pattern and the position
     * of a group is used for the calculation of the next version.</p>
     *
     * @param list                  List of versions
     * @param version               Configured version
     * @param patternForNextVersion Regex pattern with groups
     * @param sortStringPos         Position of the group - this is used for sorting and filtering
     * @return                      a list of filtered and sorted versions
     */
    @CompileDynamic
    protected static List<String> filterVersion(List<String> list, String version, String patternForNextVersion, int sortStringPos) {
        def mv = (version =~ /${patternForNextVersion}/)
        if(mv.count < 1) {
            throw new RuntimeException("Pattern for next version '${patternForNextVersion}' does not match to version '${version}'.")
        }
        String sortStr = ''
        try {
            sortStr = mv[0][sortStringPos]
        } catch (Exception ex) {
            throw new RuntimeException("Group '${sortStringPos}' with pattern '${patternForNextVersion}' does not exists for version '${version}'.")
        }
        if(! sortStr) {
            throw new RuntimeException("Group '${sortStringPos}' with pattern '${patternForNextVersion}' is empty for version '${version}'.")
        }

        boolean isLong = false
        try {
            isLong = (sortStr.toLong().toString() == sortStr)
        } catch(Exception ex) {
            log.debug('{} of {} ist not a number.', sortStr, version )
        }

        List<String> filteredList = list.findAll { it =~ /${patternForNextVersion}/ }.sort {a, b ->
            def m1 = (a =~ /${patternForNextVersion}/)
            def m2 = (a =~ /${patternForNextVersion}/)
            m1[0][sortStringPos] <=> m2[0][sortStringPos]
        }.findAll {
            def m = (it =~ /${patternForNextVersion}/)
            if(isLong) {
                try {
                    return sortStr.toLong() < "${m[0][sortStringPos]}".toLong()
                } catch(Exception ex) {
                    return false
                }
            } else {
                return sortStr < m[0][sortStringPos]
            }
        }

        return filteredList
    }

    /**
     * Create a logfile if configured with parent directory.
     * The entry will be appended to the log file.
     *
     * @param entry
     */
    private synchronized void writeToUpdateLog(String entry) {
        if(updateLogFile) {
            if(! updateLogFile.exists()) {
                updateLogFile.getParentFile().mkdirs()
            }
            updateLogFile << '\n' << entry
        }
    }

}
