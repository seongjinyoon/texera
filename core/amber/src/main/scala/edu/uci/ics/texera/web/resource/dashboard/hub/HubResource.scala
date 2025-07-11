/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package edu.uci.ics.texera.web.resource.dashboard.hub

import edu.uci.ics.texera.dao.SqlServer
import edu.uci.ics.texera.dao.jooq.generated.Tables._
import HubResource.{
  CountRequest,
  CountResponse,
  fetchDashboardDatasetsByDids,
  fetchDashboardWorkflowsByWids,
  isLikedHelper,
  recordLikeActivity,
  recordUserActivity,
  userRequest
}
import edu.uci.ics.texera.web.resource.dashboard.user.workflow.WorkflowResource.{
  DashboardWorkflow,
  baseWorkflowSelect,
  mapWorkflowEntries
}
import org.jooq.impl.DSL

import java.util.regex.Pattern
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType}
import scala.language.existentials
import scala.jdk.CollectionConverters._
import EntityTables._
import edu.uci.ics.amber.core.storage.util.LakeFSStorageClient
import edu.uci.ics.texera.dao.jooq.generated.tables.Dataset.DATASET
import edu.uci.ics.texera.dao.jooq.generated.tables.DatasetUserAccess.DATASET_USER_ACCESS
import edu.uci.ics.texera.dao.jooq.generated.tables.User.USER
import edu.uci.ics.texera.dao.jooq.generated.tables.pojos.{Dataset, DatasetUserAccess}
import edu.uci.ics.texera.web.resource.dashboard.DashboardResource.DashboardClickableFileEntry
import edu.uci.ics.texera.web.resource.dashboard.hub.ActionType.{Clone, Like, Unlike, View}
import edu.uci.ics.texera.web.resource.dashboard.user.dataset.DatasetResource.DashboardDataset

import scala.collection.mutable.ListBuffer

object HubResource {
  case class userRequest(entityId: Integer, userId: Integer, entityType: EntityType)
  case class CountRequest(entityId: Integer, entityType: EntityType)
  case class CountResponse(
      entityId: Integer,
      entityType: EntityType,
      counts: java.util.Map[ActionType, Int]
  )

  final private lazy val context = SqlServer
    .getInstance()
    .createDSLContext()

  final private val ipv4Pattern: Pattern = Pattern.compile(
    "^([0-9]{1,3}\\.){3}[0-9]{1,3}$"
  )

  /**
    * Checks if a given user has liked a specific entity.
    *
    * @param userId The ID of the user.
    * @param entityId The ID of the entity.
    * @param entityType The type of entity being checked (must be validated).
    * @return `true` if the user has liked the entity, otherwise `false`.
    */
  def isLikedHelper(userId: Integer, entityId: Integer, entityType: EntityType): Boolean = {
    val entityTables = LikeTable(entityType)
    val (table, uidColumn, idColumn) =
      (entityTables.table, entityTables.uidColumn, entityTables.idColumn)

    context
      .selectFrom(table)
      .where(
        uidColumn
          .eq(userId)
          .and(idColumn.eq(entityId))
      )
      .fetchOne() != null
  }

  /**
    * Records a user's activity in the system.
    *
    * @param request The HTTP request object to extract the user's IP address.
    * @param userId The ID of the user performing the action (default is 0 for anonymous users).
    * @param entityId The ID of the entity associated with the action.
    * @param entityType The type of entity being acted upon (validated before processing).
    * @param action The action performed by the user ("like", "unlike", "view", "clone").
    */
  def recordUserActivity(
      request: HttpServletRequest,
      userId: Integer = Integer.valueOf(0),
      entityId: Integer,
      entityType: EntityType,
      action: ActionType
  ): Unit = {
    val userIp = request.getRemoteAddr

    val query = context
      .insertInto(USER_ACTIVITY)
      .set(USER_ACTIVITY.UID, userId)
      .set(USER_ACTIVITY.ID, entityId)
      .set(USER_ACTIVITY.TYPE, entityType.value)
      .set(USER_ACTIVITY.ACTIVATE, action.value)

    if (ipv4Pattern.matcher(userIp).matches()) {
      query.set(USER_ACTIVITY.IP, userIp)
    }

    query.execute()
  }

  /**
    * Records a user's like or unlike activity for a given entity.
    *
    * @param request The HTTP request object to extract the user's IP address.
    * @param userRequest An object containing entityId, userId, and entityType.
    * @param isLike A boolean flag indicating whether the action is a like (`true`) or unlike (`false`).
    * @return `true` if the like/unlike action was recorded successfully, otherwise `false`.
    */
  def recordLikeActivity(
      request: HttpServletRequest,
      userRequest: userRequest,
      isLike: Boolean
  ): Boolean = {
    val (entityId, userId, entityType) =
      (userRequest.entityId, userRequest.userId, userRequest.entityType)
    val entityTables = LikeTable(entityType)
    val (table, uidColumn, idColumn) =
      (entityTables.table, entityTables.uidColumn, entityTables.idColumn)

    val alreadyLiked = isLikedHelper(userId, entityId, entityType)

    if (isLike && !alreadyLiked) {
      context
        .insertInto(table)
        .set(uidColumn, userId)
        .set(idColumn, entityId)
        .execute()

      recordUserActivity(request, userId, entityId, entityType, Like)
      true
    } else if (!isLike && alreadyLiked) {
      context
        .deleteFrom(table)
        .where(uidColumn.eq(userId).and(idColumn.eq(entityId)))
        .execute()

      recordUserActivity(request, userId, entityId, entityType, Unlike)
      true
    } else {
      false
    }
  }

  /**
    * Records a user's clone activity for a given entity.
    *
    * @param request The HTTP request object to extract the user's IP address.
    * @param userId The ID of the user performing the clone action.
    * @param entityId The ID of the entity being cloned.
    * @param entityType The type of entity being cloned (must be validated).
    */
  def recordCloneActivity(
      request: HttpServletRequest,
      userId: Integer,
      entityId: Integer,
      entityType: EntityType
  ): Unit = {

    val entityTables = CloneTable(entityType)
    val (table, uidColumn, idColumn) =
      (entityTables.table, entityTables.uidColumn, entityTables.idColumn)

    recordUserActivity(request, userId, entityId, entityType, Clone)

    val existingCloneRecord = context
      .selectFrom(table)
      .where(uidColumn.eq(userId))
      .and(idColumn.eq(entityId))
      .fetchOne()

    if (existingCloneRecord == null) {
      context
        .insertInto(table)
        .set(uidColumn, userId)
        .set(idColumn, entityId)
        .execute()
    }
  }

  def fetchDashboardWorkflowsByWids(wids: Seq[Integer], uid: Integer): List[DashboardWorkflow] = {
    if (wids.isEmpty) {
      return List.empty[DashboardWorkflow]
    }

    val records = baseWorkflowSelect()
      .where(WORKFLOW.WID.in(wids: _*))
      .groupBy(
        WORKFLOW.WID,
        WORKFLOW.NAME,
        WORKFLOW.DESCRIPTION,
        WORKFLOW.CREATION_TIME,
        WORKFLOW.LAST_MODIFIED_TIME,
        WORKFLOW_USER_ACCESS.PRIVILEGE,
        WORKFLOW_OF_USER.UID,
        USER.NAME
      )
      .fetch()

    mapWorkflowEntries(records, uid)
  }

  def fetchDashboardDatasetsByDids(dids: Seq[Integer], uid: Integer): List[DashboardDataset] = {
    if (dids.isEmpty) {
      return List.empty[DashboardDataset]
    }

    val records = context
      .select()
      .from(
        DATASET
          .leftJoin(DATASET_USER_ACCESS)
          .on(DATASET_USER_ACCESS.DID.eq(DATASET.DID))
          .leftJoin(USER)
          .on(USER.UID.eq(DATASET.OWNER_UID))
      )
      .where(DATASET.DID.in(dids: _*))
      .groupBy(
        DATASET.DID,
        DATASET.NAME,
        DATASET.DESCRIPTION,
        DATASET.OWNER_UID,
        USER.NAME,
        DATASET_USER_ACCESS.DID,
        DATASET_USER_ACCESS.UID,
        USER.UID
      )
      .fetch()

    records.asScala
      .map { record =>
        val dataset = record.into(DATASET).into(classOf[Dataset])
        val datasetAccess = record.into(DATASET_USER_ACCESS).into(classOf[DatasetUserAccess])
        val ownerEmail = record.into(USER).getEmail
        DashboardDataset(
          isOwner = if (uid == null) false else dataset.getOwnerUid == uid,
          dataset = dataset,
          accessPrivilege = datasetAccess.getPrivilege,
          ownerEmail = ownerEmail,
          size = LakeFSStorageClient.retrieveRepositorySize(dataset.getName)
        )
      }
      .toList
      .distinctBy(_.dataset.getDid)
  }
}

@Produces(Array(MediaType.APPLICATION_JSON))
@Path("/hub")
class HubResource {
  final private lazy val context = SqlServer
    .getInstance()
    .createDSLContext()

  @GET
  @Path("/count")
  def getPublishedWorkflowCount(@QueryParam("entityType") entityType: EntityType): Integer = {
    val entityTables = BaseEntityTable(entityType)
    val (table, isPublicColumn) = (entityTables.table, entityTables.isPublicColumn)

    context
      .selectCount()
      .from(table)
      .where(isPublicColumn.eq(true))
      .fetchOne(0, classOf[Integer])
  }

  @GET
  @Path("/isLiked")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def isLiked(
      @QueryParam("workflowId") entityId: Integer,
      @QueryParam("userId") userId: Integer,
      @QueryParam("entityType") entityType: EntityType
  ): Boolean = {
    isLikedHelper(userId, entityId, entityType)
  }

  @POST
  @Path("/like")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def likeWorkflow(
      @Context request: HttpServletRequest,
      likeRequest: userRequest
  ): Boolean = {
    recordLikeActivity(request, likeRequest, isLike = true)
  }

  @POST
  @Path("/unlike")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def unlikeWorkflow(
      @Context request: HttpServletRequest,
      unlikeRequest: userRequest
  ): Boolean = {
    recordLikeActivity(request, unlikeRequest, isLike = false)
  }

  @POST
  @Path("/view")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def viewWorkflow(
      @Context request: HttpServletRequest,
      viewRequest: userRequest
  ): Int = {

    val (entityID, userId, entityType) =
      (viewRequest.entityId, viewRequest.userId, viewRequest.entityType)

    val entityTables = ViewCountTable(entityType)
    val (table, idColumn, viewCountColumn) =
      (entityTables.table, entityTables.idColumn, entityTables.viewCountColumn)

    val record = context
      .insertInto(table)
      .set(idColumn, entityID)
      .set(viewCountColumn, Integer.valueOf(1))
      .onDuplicateKeyUpdate()
      .set(viewCountColumn, viewCountColumn.add(1))
      .returning(viewCountColumn)
      .fetchOne()

    recordUserActivity(request, userId, entityID, entityType, View)

    record.get(viewCountColumn)
  }

  /**
    * Unified endpoint to fetch the top N (here N = 8) public entities for a given entity type,
    * grouped by specified action types, with optional user context.
    *
    * @param entityType   The EntityType enum value (Workflow or Dataset) to query.
    * @param actionTypes  Optional list of ActionType enums to include (Like, Clone).
    *                     If omitted or empty, defaults to [Like, Clone].
    * @param uid          Optional user ID (Integer) for user-specific context.
    *                     If null or -1, no per-user flags are applied.
    * @return             A Map from each actionType.value (e.g. "like", "clone")
    *                     to a List of DashboardClickableFileEntry containing the top 8
    *                     public entities of that type.
    */
  @GET
  @Path("/getTops")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getTops(
      @QueryParam("entityType") entityType: EntityType,
      @QueryParam("actionTypes") actionTypes: java.util.List[ActionType],
      @QueryParam("uid") uid: Integer
  ): java.util.Map[String, java.util.List[DashboardClickableFileEntry]] = {
    val baseTable = BaseEntityTable(entityType)
    val isPublicColumn = baseTable.isPublicColumn
    val baseIdColumn = baseTable.idColumn

    val currentUid: Integer =
      if (uid == null || uid == -1) null
      else Integer.valueOf(uid)

    val types: Seq[ActionType] =
      if (actionTypes != null && !actionTypes.isEmpty)
        actionTypes.asScala.toList.distinct
      else
        Seq(ActionType.Like, ActionType.Clone)

    val result: Map[String, java.util.List[DashboardClickableFileEntry]] =
      types.map { act =>
        val (table, idColumn) = act match {
          case ActionType.Like =>
            val lt = LikeTable(entityType)
            (lt.table, lt.idColumn)
          case ActionType.Clone =>
            val ct = CloneTable(entityType)
            (ct.table, ct.idColumn)
          case other =>
            throw new BadRequestException(
              s"Unsupported actionType: '$other'. Supported: [like, clone]"
            )
        }

        val topIds: Seq[Integer] = context
          .select(idColumn)
          .from(table)
          .join(baseTable.table)
          .on(idColumn.eq(baseIdColumn))
          .where(isPublicColumn.eq(true))
          .groupBy(idColumn)
          .orderBy(DSL.count(idColumn).desc())
          .limit(8)
          .fetchInto(classOf[Integer])
          .asScala
          .toSeq

        val entries: Seq[DashboardClickableFileEntry] =
          if (entityType == EntityType.Workflow) {
            fetchDashboardWorkflowsByWids(topIds, currentUid).map { w =>
              DashboardClickableFileEntry(
                resourceType = entityType.value,
                workflow = Some(w),
                project = None,
                dataset = None
              )
            }
          } else if (entityType == EntityType.Dataset) {
            fetchDashboardDatasetsByDids(topIds, currentUid).map { d =>
              DashboardClickableFileEntry(
                resourceType = entityType.value,
                workflow = None,
                project = None,
                dataset = Some(d)
              )
            }
          } else {
            Seq.empty
          }

        act.value -> entries.toList.asJava
      }.toMap

    result.asJava
  }

  /**
    * Batch endpoint to fetch counts for one or more entities, optionally filtered by action types.
    *
    * Example requests:
    *   // All counts for two entities:
    *   // GET /hub/counts?
    *   //     entityType=workflow&entityId=123&
    *   //     entityType=dataset&entityId=456
    *
    *   // Only "view" and "like" counts for the same pair:
    *   // GET /hub/counts?
    *   //     entityType=workflow&entityId=123&
    *   //     entityType=dataset&entityId=456&
    *   //     actionType=view&actionType=like
    *
    * @param entityTypes   List of entity types to query (enum EntityType), e.g. [Workflow, Dataset].
    * @param entityIds     Parallel list of entity IDs, must be the same length as entityTypes.
    * @param actionTypes   (Optional) List of action types to include (enum ActionType).
    *                      Supported values: View, Like, Clone, Unlike. If empty or null, all actions are returned.
    * @return              A list of CountResponse objects, one per (entityType, entityId) pair,
    *                      each containing the counts for the requested actions.
    * @throws BadRequestException if entityTypes or entityIds are missing, empty, mismatched in length,
    *         or if actionTypes contains an unsupported value.
    */
  @GET
  @Path("/counts")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getCounts(
      @QueryParam("entityType") entityTypes: java.util.List[EntityType],
      @QueryParam("entityId") entityIds: java.util.List[Integer],
      @QueryParam("actionType") actionTypes: java.util.List[ActionType]
  ): java.util.List[CountResponse] = {
    if (
      entityTypes == null || entityIds == null || entityTypes.isEmpty || entityTypes
        .size() != entityIds.size()
    )
      throw new BadRequestException(
        "Both 'entityType' and 'entityId' query parameters must be provided, and lists must have equal length."
      )

    val reqs: List[CountRequest] = entityTypes.asScala
      .zip(entityIds.asScala)
      .map {
        case (etype, id) => CountRequest(id, etype)
      }
      .toList

    val requestedActions: Seq[ActionType] =
      if (actionTypes != null && !actionTypes.isEmpty)
        actionTypes.asScala.toList.distinct
      else
        Seq(ActionType.View, ActionType.Like, ActionType.Clone)

    val grouped: Map[EntityType, Seq[Integer]] =
      reqs.groupBy(_.entityType).view.mapValues(_.map(_.entityId)).toMap

    val buffer = ListBuffer[CountResponse]()

    grouped.foreach {
      case (etype, ids) =>
        val viewTbl = ViewCountTable(etype)
        val viewMap: Map[Int, Int] =
          if (requestedActions.contains(ActionType.View)) {
            val raw = context
              .select(viewTbl.idColumn, viewTbl.viewCountColumn)
              .from(viewTbl.table)
              .where(viewTbl.idColumn.in(ids: _*))
              .fetchMap(viewTbl.idColumn, viewTbl.viewCountColumn)
              .asScala
              .map { case (k, v) => k.intValue() -> v.intValue() }
              .toMap

            val missing = ids.filterNot(id => raw.contains(id.intValue()))

            missing.foreach { id =>
              context
                .insertInto(viewTbl.table)
                .set(viewTbl.idColumn, id)
                .set(viewTbl.viewCountColumn, Integer.valueOf(0))
                .onDuplicateKeyIgnore()
                .execute()
            }

            raw ++ missing.map(id => id.intValue() -> 0).toMap
          } else Map.empty

        val likeTbl = LikeTable(etype)
        val likeMap: Map[Int, Int] =
          if (requestedActions.contains(ActionType.Like)) {
            context
              .select(likeTbl.idColumn, DSL.count().`as`("cnt"))
              .from(likeTbl.table)
              .where(likeTbl.idColumn.in(ids: _*))
              .groupBy(likeTbl.idColumn)
              .fetch()
              .asScala
              .map { r =>
                r.get(likeTbl.idColumn).intValue() ->
                  r.get("cnt", classOf[Integer]).intValue()
              }
              .toMap
          } else Map.empty

        val cloneMap: Map[Int, Int] =
          if (requestedActions.contains(ActionType.Clone) && etype != EntityType.Dataset) {
            val cloneTbl = CloneTable(etype)
            context
              .select(cloneTbl.idColumn, DSL.count().`as`("cnt"))
              .from(cloneTbl.table)
              .where(cloneTbl.idColumn.in(ids: _*))
              .groupBy(cloneTbl.idColumn)
              .fetch()
              .asScala
              .map { r =>
                r.get(cloneTbl.idColumn).intValue() ->
                  r.get("cnt", classOf[Integer]).intValue()
              }
              .toMap
          } else Map.empty

        reqs.filter(_.entityType == etype).foreach { req =>
          val key = req.entityId.intValue()
          val counts = scala.collection.mutable.Map[ActionType, Int]()
          if (requestedActions.contains(ActionType.View))
            counts(ActionType.View) = viewMap.getOrElse(key, 0)
          if (requestedActions.contains(ActionType.Like))
            counts(ActionType.Like) = likeMap.getOrElse(key, 0)
          if (requestedActions.contains(ActionType.Clone))
            counts(ActionType.Clone) = cloneMap.getOrElse(key, 0)

          buffer += CountResponse(req.entityId, etype, counts.asJava)
        }
    }

    buffer.toList.asJava
  }
}
