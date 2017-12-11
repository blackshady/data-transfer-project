/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dataportabilityproject.webapp;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dataportabilityproject.ServiceProviderRegistry;
import org.dataportabilityproject.job.JobUtils;
import org.dataportabilityproject.job.PortabilityJobFactory;
import org.dataportabilityproject.shared.LogUtils;
import org.dataportabilityproject.shared.PortableDataType;
import org.dataportabilityproject.shared.auth.AuthFlowInitiator;
import org.dataportabilityproject.shared.auth.OnlineAuthDataGenerator;
import org.dataportabilityproject.job.JobDao;
import org.dataportabilityproject.job.PortabilityJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to process the configuration submitted via the form and responds with the redirect
 * for the export service.
 */
@RestController
public class ConfigureController {

  @Autowired
  private ServiceProviderRegistry serviceProviderRegistry;
  @Autowired
  private JobDao jobDao;
  @Autowired
  private PortabilityJobFactory jobFactory;

  /**
   * Sets the selected service for import or export and kicks off the auth flow.
   */
  @RequestMapping(path = "/configure", method = RequestMethod.POST)
  public void configure(HttpServletRequest request, HttpServletResponse response,
      @CookieValue(value = JsonKeys.ID_COOKIE_KEY, required = false) String encodedIdCookie)
      throws Exception {

    LogUtils.log("Configure: %s", request.getRequestURI());
    for (String key : request.getParameterMap().keySet()) {
      LogUtils.log("Parameter key: %s, value: %s", key, request.getParameter(key));
    }

    // TODO: Determine what to do if previous job exists in the session or remove this code
    String existingJobId = null;
    PortabilityJob existingJob = null;
    if (!Strings.isNullOrEmpty(encodedIdCookie)) {
      existingJobId = JobUtils.decodeId(encodedIdCookie);
      LogUtils.log("Found existing cookie, ignoring previous values");
      existingJob = jobDao.findExistingJob(existingJobId);
      if(existingJob != null) {
        LogUtils.log("Found existing job, ignoring previous values");
      } else {
        LogUtils.log("Found existing cookie but no job");
      }
    }

    // Either encodedId in cookie was empty or the job it represented was not found, create a new one
    String dataTypeStr = getParam(request, "dataType");
    Preconditions
        .checkArgument(!Strings.isNullOrEmpty(dataTypeStr), "Missing valid dataTypeParam: %s",
            dataTypeStr);
    PortableDataType dataType = JobUtils.getDataType(dataTypeStr);

    String exportService = getParam(request, "exportService");
    Preconditions.checkArgument(JobUtils.isValidService(exportService, true),
        "Missing valid exportService: %s", exportService);

    String importService = getParam(request, "importService");
    Preconditions.checkArgument(JobUtils.isValidService(importService, false),
        "Missing valid importService: %s", importService);

    PortabilityJob newJob = jobFactory.create(dataType, exportService, importService);
    jobDao.insertJob(newJob);

    // Set new cookie
    Cookie cookie = new Cookie(JsonKeys.ID_COOKIE_KEY, JobUtils.encodeId(newJob));
    LogUtils.log("Set new cookie with key: %s, value: %s", JsonKeys.ID_COOKIE_KEY, JobUtils.encodeId(newJob));
    response.addCookie(cookie);

    // Lookup job, even if just recently created
    PortabilityJob job = lookupJob(newJob.id());
    Preconditions.checkState(job != null, "Job required");

    // TODO: Validate job before going further

    // Obtain the OnlineAuthDataGenerator
    OnlineAuthDataGenerator generator = serviceProviderRegistry
        .getOnlineAuth(job.exportService(), dataType);
    Preconditions.checkNotNull(generator, "Generator not found for type: %s, service: %s",
        dataType, job.exportService());

    // Auth authUrl
    AuthFlowInitiator authFlowInitiator = generator
        .generateAuthUrl(PortabilityServerFlags.baseApiUrl(), JobUtils.encodeId(newJob));
    Preconditions.checkNotNull(authFlowInitiator,"AuthFlowInitiator not found for type: %s, service: %s",
        dataType, job.exportService());

    // Store initial auth data
    if (authFlowInitiator.initialAuthData() != null) {
      PortabilityJob jobBeforeInitialData = lookupJob(job.id());
      PortabilityJob updatedJob = JobUtils
          .setInitialAuthData(job, authFlowInitiator.initialAuthData(), true);
      jobDao.updateJob(updatedJob);
    }

    // Send the authUrl for the client to redirect to service authorization
    LogUtils.log("Redirecting to: %s", authFlowInitiator.authUrl());
    response.sendRedirect(authFlowInitiator.authUrl());
  }

  /**
   * Looks up job and does checks that it exists.
   */
  private PortabilityJob lookupJob(String id) {
    PortabilityJob job = jobDao.findExistingJob(id);
    Preconditions.checkState(null != job, "existingJob not found for id: %s", id);
    return job;
  }

  // TODO: Determine how to get client to submit 'clean' values
  // Hack to strip angular indexing in option values
  private static String getParam(HttpServletRequest request, String name) {
    String value = request.getParameterValues(name)[0];
    String trimmed = value.substring(value.indexOf(":") + 1).trim();
    LogUtils.log("Converted: %s , result: %s", value, trimmed);
    return trimmed;
  }
}
