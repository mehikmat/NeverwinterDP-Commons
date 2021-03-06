package com.neverwinterdp.hadoop.yarn.app;

import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import com.beust.jcommander.JCommander;

public class AppClient  {
  
  public AppClientMonitor run(String[] args) throws Exception {
    return run(args, new YarnConfiguration()) ;
  }
  
  public AppClientMonitor run(String[] args, Configuration conf) throws Exception {
    try {
      AppMasterConfig appOpts = new AppMasterConfig() ;
      new JCommander(appOpts, args) ;
      appOpts.overrideConfiguration(conf);
      
      uploadApp(appOpts);
      
      System.out.println("Create YarnClient") ;
      YarnClient yarnClient = YarnClient.createYarnClient();
      yarnClient.init(conf);
      yarnClient.start();

      System.out.println("Create YarnClientApplication via YarnClient") ;
      YarnClientApplication app = yarnClient.createApplication();

      System.out.println("Set up the container launch context for the application master") ;
      ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);
      amContainer.setCommands(appOpts.buildAppMasterCommands()) ;

      System.out.println("Setup the app classpath and resources") ;
      if(appOpts.appHome != null) {
        amContainer.setLocalResources(this.createLocalResources(conf, appOpts));
      }
      System.out.println("Setup the classpath for ApplicationMaster") ;
      Map<String, String> appMasterEnv = new HashMap<String, String>();
      Util.setupAppMasterEnv(appOpts.miniClusterEnv, conf, appMasterEnv);
      amContainer.setEnvironment(appMasterEnv);

      System.out.println("Set up resource type requirements for ApplicationMaster") ;
      Resource resource = Records.newRecord(Resource.class);
      resource.setMemory(256);
      resource.setVirtualCores(1);

      System.out.println("Finally, set-up ApplicationSubmissionContext for the application");
      ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
      appContext.setApplicationName(appOpts.appName); // application name
      appContext.setAMContainerSpec(amContainer);
      appContext.setResource(resource);
      appContext.setQueue("default"); // queue 

      // Submit application
      ApplicationId appId = appContext.getApplicationId();
      System.out.println("Submitting application " + appId);
      yarnClient.submitApplication(appContext);
      return new AppClientMonitor(yarnClient, appId) ;
    } catch(Exception ex) {
      ex.printStackTrace(); 
      throw ex ;
    }
  }
  
  public void uploadApp(AppMasterConfig appOpts) throws Exception {
    if(appOpts.uploadApp == null) return ;
    HdfsConfiguration hdfsConf = new HdfsConfiguration() ;
    appOpts.overrideConfiguration(hdfsConf);
    FileSystem fs = FileSystem.get(hdfsConf);
    DistributedFileSystem dfs = (DistributedFileSystem)fs;
    Path appHomePath = new Path(appOpts.appHome) ;
    if(dfs.exists(appHomePath)) {
      dfs.delete(appHomePath, true) ;
    }
    dfs.copyFromLocalFile(false, true, new Path(appOpts.uploadApp), appHomePath);
  }
  
  Map<String, LocalResource> createLocalResources(Configuration conf, AppMasterConfig opts) throws Exception {
    Map<String, LocalResource> libs = new HashMap<String, LocalResource>() ;
    FileSystem fs = FileSystem.get(conf) ;
    RemoteIterator<LocatedFileStatus> itr = fs.listFiles(new Path(opts.appHome), true) ;
    while(itr.hasNext()) {
      FileStatus fstatus = itr.next() ;
      Path fpath = fstatus.getPath() ;
      LocalResource libJar = Records.newRecord(LocalResource.class);
      libJar.setResource(ConverterUtils.getYarnUrlFromPath(fpath));
      libJar.setSize(fstatus.getLen());
      libJar.setTimestamp(fstatus.getModificationTime());
      libJar.setType(LocalResourceType.FILE);
      libJar.setVisibility(LocalResourceVisibility.PUBLIC);
      libs.put(fpath.getName(), libJar) ;
    }
    return libs ;
  }
  
  static public void main(String[] args) throws Exception {
    AppClient appClient = new AppClient() ;
    AppClientMonitor reporter = appClient.run(args);
    reporter.monitor(); 
    reporter.report(System.out);
  }
}