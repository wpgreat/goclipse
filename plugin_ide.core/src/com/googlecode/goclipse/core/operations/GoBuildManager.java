/*******************************************************************************
 * Copyright (c) 2015, 2015 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package com.googlecode.goclipse.core.operations;

import static melnorme.lang.ide.core.utils.ResourceUtils.loc;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Collection;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.googlecode.goclipse.core.GoEnvironmentPrefs;
import com.googlecode.goclipse.core.GoProjectEnvironment;
import com.googlecode.goclipse.core.GoProjectPrefConstants;
import com.googlecode.goclipse.tooling.GoBuildOutputProcessor;
import com.googlecode.goclipse.tooling.GoPackageName;
import com.googlecode.goclipse.tooling.env.GoEnvironment;

import melnorme.lang.ide.core.LangCore;
import melnorme.lang.ide.core.operations.OperationInfo;
import melnorme.lang.ide.core.operations.ToolMarkersUtil;
import melnorme.lang.ide.core.operations.build.BuildManager;
import melnorme.lang.ide.core.operations.build.BuildTarget;
import melnorme.lang.ide.core.operations.build.CommonBuildTargetOperation;
import melnorme.lang.ide.core.project_model.AbstractBundleInfo;
import melnorme.lang.ide.core.project_model.AbstractBundleInfo.BuildConfiguration;
import melnorme.lang.ide.core.project_model.LangBundleModel;
import melnorme.lang.ide.core.project_model.ProjectBuildInfo;
import melnorme.lang.ide.core.utils.ResourceUtils;
import melnorme.lang.tooling.data.StatusLevel;
import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.concurrency.OperationCancellation;
import melnorme.utilbox.core.CommonException;
import melnorme.utilbox.misc.Location;
import melnorme.utilbox.process.ExternalProcessHelper.ExternalProcessResult;

public class GoBuildManager extends BuildManager {
	
	public GoBuildManager(LangBundleModel<? extends AbstractBundleInfo> bundleModel) {
		super(bundleModel);
	}
	
	@Override
	public BuildTarget getBuildTargetFor(ProjectBuildInfo projectBuildInfo, String targetName) throws CommonException {
		BuildTarget buildTarget = super.getBuildTargetFor(projectBuildInfo, targetName);
		if(buildTarget != null) {
			return buildTarget;
		}
		
		IProject project = projectBuildInfo.getProject();
		GoEnvironment goEnv = GoProjectEnvironment.getGoEnvironment(project);
		
		GoPackageName goPackage = GoPackageName.createValid(targetName);
		Location goPackageFile = goEnv.getBinFileForGoPackage(loc(project.getLocation()), goPackage);
		
		BuildConfiguration buildConfig = new BuildConfiguration(targetName, goPackageFile.toPath());
		return createBuildTarget(targetName, buildConfig, false);
	}
	
	@Override
	public CommonBuildTargetOperation createBuildTargetSubOperation(OperationInfo parentOpInfo, IProject project,
			Path buildToolPath, BuildTarget buildTarget, boolean fullBuild) {
		return new GoBuildTargetOperation(parentOpInfo, project, buildToolPath, buildTarget, fullBuild);
	}
	
	/* -----------------  ----------------- */
	
	/* FIXME: to do build according to BuildTarget/GoPackage */
	
	protected class GoBuildTargetOperation extends CommonBuildTargetOperation {
		
		protected static final String ERROR_SrcRootContainsGoFiles = 
				"The Go `src` directory at `{0}` contains .go files. " +
				"This is not allowed, these files will be ignored.\n" + 
				"Instead, all .go files should be in a subdirectory of `src`, " + 
				"so that they will be part of a Go package. " + 
				"This is so they can be built using the `./...` pattern, or imported by other Go files.";
		
		protected GoEnvironment goEnv;
		protected Location sourceRootDir;
		
		public GoBuildTargetOperation(OperationInfo parentOpInfo, IProject project, Path buildToolPath,
				BuildTarget buildTarget, boolean fullBuild) {
			super(parentOpInfo, project, buildToolPath, buildTarget, fullBuild);
		}
		
		@Override
		public void execute(IProgressMonitor pm) throws CoreException, CommonException, OperationCancellation {
			
			Location projectLocation = ResourceUtils.getProjectLocation(project);
			
			goEnv = getValidGoEnvironment(project);
			if(GoProjectEnvironment.isProjectInsideGoPath(project, goEnv.getGoPath())) {
				sourceRootDir = projectLocation;
			} else {
				sourceRootDir = projectLocation.resolve_valid("src");
				
				checkGoFilesInSourceRoot();
			}
			
			ProcessBuilder pb = createBuildPB();
			
			ExternalProcessResult buildAllResult = runBuildTool(pm, pb);
			doBuild_processBuildResult(buildAllResult);
		}
		
		protected void checkGoFilesInSourceRoot() throws CoreException {
			CheckSrcFolderRootFilesWithNoPackage srcCheck = new CheckSrcFolderRootFilesWithNoPackage();
			
			srcCheck.checkDir(sourceRootDir);
			
			if(srcCheck.containsGoSources) {
				LangCore.getToolManager().notifyMessage(StatusLevel.WARNING, "Go build: Warning!", 
					MessageFormat.format(ERROR_SrcRootContainsGoFiles, sourceRootDir));
			}
		}
		
		protected ProcessBuilder createBuildPB() throws CoreException, CommonException {
			ArrayList2<String> goBuildCmdLine = getGoToolCommandLine();
			goBuildCmdLine.addElements();
			goBuildCmdLine.addElements(GoProjectPrefConstants.GO_BUILD_OPTIONS.getParsedArguments(project));
			
			return goEnv.createProcessBuilder(goBuildCmdLine, sourceRootDir);
		}
		
		protected void doBuild_processBuildResult(ExternalProcessResult buildAllResult) 
				throws CoreException, CommonException {
			GoBuildOutputProcessor buildOutput = new GoBuildOutputProcessor() {
				@Override
				protected void handleMessageParseError(CommonException ce) {
					LangCore.logError(ce.getMessage(), ce.getCause());
				}
			};
			buildOutput.parseOutput(buildAllResult);
			
			ToolMarkersUtil.addErrorMarkers(buildOutput.getBuildErrors(), sourceRootDir);
		}
		
	}
	
	protected static GoEnvironment getValidGoEnvironment(IProject project) throws CoreException {
		try {
			return GoProjectEnvironment.getValidatedGoEnvironment(project);
		} catch (CommonException ce) {
			throw LangCore.createCoreException(ce);
		}
	}
	
	protected static ArrayList2<String> getGoToolCommandLine() throws CoreException {
		String compilerPath = GoEnvironmentPrefs.COMPILER_PATH.get();
		
		if(compilerPath.isEmpty()) {
			throw LangCore.createCoreException("Compiler Path not defined.", null);
		}
		return new ArrayList2<>(compilerPath);
	}
	
	protected static void addSourcePackagesToCmdLine(final IProject project, ArrayList2<String> goBuildCmdLine,
			GoEnvironment goEnvironment) throws CoreException {
		Collection<GoPackageName> sourcePackages = GoProjectEnvironment.getSourcePackages(project, goEnvironment);
		for (GoPackageName goPackageName : sourcePackages) {
			goBuildCmdLine.add(goPackageName.getFullNameAsString());
		}
	}
	
}