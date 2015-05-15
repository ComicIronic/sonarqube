/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.component.ws;

import com.google.common.io.Resources;
import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.component.SnapshotTesting;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.component.db.SnapshotDao;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsTester;
import org.sonar.test.JsonAssert;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectsGhostsActionTest {

  @ClassRule
  public static DbTester db = new DbTester();
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  WsTester ws;

  DbClient dbClient;
  DbSession dbSession;

  @Before
  public void setUp() {
    dbClient = new DbClient(db.database(), db.myBatis(), new ComponentDao(System2.INSTANCE), new SnapshotDao(System2.INSTANCE));
    dbSession = dbClient.openSession(false);
    ws = new WsTester(new ProjectsWs(new ProjectsGhostsAction(dbClient, userSessionRule)));
    db.truncateTables();
  }

  @After
  public void tearDown() throws Exception {
    dbSession.close();
  }

  @Test
  public void ghost_projects_without_analyzed_projects() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    insertNewGhostProject("1");
    insertNewGhostProject("2");
    insertNewActiveProject("3");

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts").execute();

    result.assertJson(getClass(), "all-projects.json");
    assertThat(result.outputAsString()).doesNotContain("analyzed-uuid-3");
  }

  @Test
  public void ghost_projects_with_correct_pagination() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    for (int i = 1; i <= 10; i++) {
      insertNewGhostProject(String.valueOf(i));
    }

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts")
      .setParam(Param.PAGE, "3")
      .setParam(Param.PAGE_SIZE, "4")
      .execute();

    result.assertJson(getClass(), "pagination.json");
    assertThat(StringUtils.countMatches(result.outputAsString(), "ghost-uuid-")).isEqualTo(2);
  }

  @Test
  public void ghost_projects_with_chosen_fields() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    insertNewGhostProject("1");

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts")
      .setParam(Param.FIELDS, "name")
      .execute();

    assertThat(result.outputAsString()).contains("uuid", "name")
      .doesNotContain("key")
      .doesNotContain("creationDate");
  }

  @Test
  public void ghost_projects_with_partial_query_on_name() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);

    insertNewGhostProject("10");
    insertNewGhostProject("11");
    insertNewGhostProject("2");

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts")
      .setParam(Param.TEXT_QUERY, "name-1")
      .execute();

    assertThat(result.outputAsString()).contains("ghost-name-10", "ghost-name-11")
      .doesNotContain("ghost-name-2");
  }

  @Test
  public void ghost_projects_with_partial_query_on_key() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);

    insertNewGhostProject("1");

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts")
      .setParam(Param.TEXT_QUERY, "GHOST-key")
      .execute();

    assertThat(result.outputAsString()).contains("ghost-key-1");
  }

  @Test
  public void ghost_projects_base_on_json_example() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    ComponentDto hBaseProject = ComponentTesting.newProjectDto("ce4c03d6-430f-40a9-b777-ad877c00aa4d")
      .setKey("org.apache.hbas:hbase")
      .setName("HBase")
      .setCreatedAt(DateUtils.parseDateTime("2015-03-04T23:03:44+0100"));
    hBaseProject = dbClient.componentDao().insert(dbSession, hBaseProject);
    dbClient.snapshotDao().insert(dbSession, SnapshotTesting.createForProject(hBaseProject)
      .setStatus(SnapshotDto.STATUS_UNPROCESSED));
    ComponentDto roslynProject = ComponentTesting.newProjectDto("c526ef20-131b-4486-9357-063fa64b5079")
      .setKey("com.microsoft.roslyn:roslyn")
      .setName("Roslyn")
      .setCreatedAt(DateUtils.parseDateTime("2013-03-04T23:03:44+0100"));
    roslynProject = dbClient.componentDao().insert(dbSession, roslynProject);
    dbClient.snapshotDao().insert(dbSession, SnapshotTesting.createForProject(roslynProject)
      .setStatus(SnapshotDto.STATUS_UNPROCESSED));
    dbSession.commit();

    WsTester.Result result = ws.newGetRequest("api/projects", "ghosts").execute();

    JsonAssert.assertJson(result.outputAsString()).isSimilarTo(Resources.getResource(getClass(), "projects-example-ghosts.json"));
  }

  @Test(expected = ForbiddenException.class)
  public void fail_if_does_not_have_sufficient_rights() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.USER, UserRole.ISSUE_ADMIN, UserRole.CODEVIEWER);

    ws.newGetRequest("api/projects", "ghosts").execute();
  }

  private void insertNewGhostProject(String id) {
    ComponentDto project = ComponentTesting
      .newProjectDto("ghost-uuid-" + id)
      .setName("ghost-name-" + id)
      .setKey("ghost-key-" + id);
    project = dbClient.componentDao().insert(dbSession, project);
    SnapshotDto snapshot = SnapshotTesting.createForProject(project)
      .setStatus(SnapshotDto.STATUS_UNPROCESSED);
    dbClient.snapshotDao().insert(dbSession, snapshot);
    dbSession.commit();
  }

  private void insertNewActiveProject(String id) {
    ComponentDto project = ComponentTesting
      .newProjectDto("analyzed-uuid-" + id)
      .setName("analyzed-name-" + id)
      .setKey("analyzed-key-" + id);
    project = dbClient.componentDao().insert(dbSession, project);
    SnapshotDto snapshot = SnapshotTesting.createForProject(project);
    dbClient.snapshotDao().insert(dbSession, snapshot);
    dbSession.commit();
  }
}