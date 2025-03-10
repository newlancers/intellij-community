// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.entities.test.api

import com.intellij.workspaceModel.storage.*
import com.intellij.workspaceModel.storage.EntityInformation
import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.EntityStorage
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.GeneratedCodeImplVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.impl.ConnectionId
import com.intellij.workspaceModel.storage.impl.EntityLink
import com.intellij.workspaceModel.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.UsedClassesCollector
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityBase
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityData
import com.intellij.workspaceModel.storage.impl.extractOneToManyChildren
import com.intellij.workspaceModel.storage.impl.extractOneToManyParent
import com.intellij.workspaceModel.storage.impl.updateOneToManyChildrenOfParent
import com.intellij.workspaceModel.storage.impl.updateOneToManyParentOfChild
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type
import org.jetbrains.deft.annotations.Child

@GeneratedCodeApiVersion(1)
@GeneratedCodeImplVersion(1)
open class TreeMultiparentLeafEntityImpl(val dataSource: TreeMultiparentLeafEntityData) : TreeMultiparentLeafEntity, WorkspaceEntityBase() {

  companion object {
    internal val MAINPARENT_CONNECTION_ID: ConnectionId = ConnectionId.create(TreeMultiparentRootEntity::class.java,
                                                                              TreeMultiparentLeafEntity::class.java,
                                                                              ConnectionId.ConnectionType.ONE_TO_MANY, true)
    internal val LEAFPARENT_CONNECTION_ID: ConnectionId = ConnectionId.create(TreeMultiparentLeafEntity::class.java,
                                                                              TreeMultiparentLeafEntity::class.java,
                                                                              ConnectionId.ConnectionType.ONE_TO_MANY, true)
    internal val CHILDREN_CONNECTION_ID: ConnectionId = ConnectionId.create(TreeMultiparentLeafEntity::class.java,
                                                                            TreeMultiparentLeafEntity::class.java,
                                                                            ConnectionId.ConnectionType.ONE_TO_MANY, true)

    val connections = listOf<ConnectionId>(
      MAINPARENT_CONNECTION_ID,
      LEAFPARENT_CONNECTION_ID,
      CHILDREN_CONNECTION_ID,
    )

  }

  override val data: String
    get() = dataSource.data

  override val mainParent: TreeMultiparentRootEntity?
    get() = snapshot.extractOneToManyParent(MAINPARENT_CONNECTION_ID, this)

  override val leafParent: TreeMultiparentLeafEntity?
    get() = snapshot.extractOneToManyParent(LEAFPARENT_CONNECTION_ID, this)

  override val children: List<TreeMultiparentLeafEntity>
    get() = snapshot.extractOneToManyChildren<TreeMultiparentLeafEntity>(CHILDREN_CONNECTION_ID, this)!!.toList()

  override fun connectionIdList(): List<ConnectionId> {
    return connections
  }

  class Builder(var result: TreeMultiparentLeafEntityData?) : ModifiableWorkspaceEntityBase<TreeMultiparentLeafEntity>(), TreeMultiparentLeafEntity.Builder {
    constructor() : this(TreeMultiparentLeafEntityData())

    override fun applyToBuilder(builder: MutableEntityStorage) {
      if (this.diff != null) {
        if (existsInBuilder(builder)) {
          this.diff = builder
          return
        }
        else {
          error("Entity TreeMultiparentLeafEntity is already created in a different builder")
        }
      }

      this.diff = builder
      this.snapshot = builder
      addToBuilder()
      this.id = getEntityData().createEntityId()
      // After adding entity data to the builder, we need to unbind it and move the control over entity data to builder
      // Builder may switch to snapshot at any moment and lock entity data to modification
      this.result = null

      // Process linked entities that are connected without a builder
      processLinkedEntities(builder)
      checkInitialization() // TODO uncomment and check failed tests
    }

    fun checkInitialization() {
      val _diff = diff
      if (!getEntityData().isEntitySourceInitialized()) {
        error("Field WorkspaceEntity#entitySource should be initialized")
      }
      if (!getEntityData().isDataInitialized()) {
        error("Field TreeMultiparentLeafEntity#data should be initialized")
      }
      // Check initialization for list with ref type
      if (_diff != null) {
        if (_diff.extractOneToManyChildren<WorkspaceEntityBase>(CHILDREN_CONNECTION_ID, this) == null) {
          error("Field TreeMultiparentLeafEntity#children should be initialized")
        }
      }
      else {
        if (this.entityLinks[EntityLink(true, CHILDREN_CONNECTION_ID)] == null) {
          error("Field TreeMultiparentLeafEntity#children should be initialized")
        }
      }
    }

    override fun connectionIdList(): List<ConnectionId> {
      return connections
    }

    // Relabeling code, move information from dataSource to this builder
    override fun relabel(dataSource: WorkspaceEntity, parents: Set<WorkspaceEntity>?) {
      dataSource as TreeMultiparentLeafEntity
      if (this.entitySource != dataSource.entitySource) this.entitySource = dataSource.entitySource
      if (this.data != dataSource.data) this.data = dataSource.data
      if (parents != null) {
        val mainParentNew = parents.filterIsInstance<TreeMultiparentRootEntity?>().singleOrNull()
        if ((mainParentNew == null && this.mainParent != null) || (mainParentNew != null && this.mainParent == null) || (mainParentNew != null && this.mainParent != null && (this.mainParent as WorkspaceEntityBase).id != (mainParentNew as WorkspaceEntityBase).id)) {
          this.mainParent = mainParentNew
        }
        val leafParentNew = parents.filterIsInstance<TreeMultiparentLeafEntity?>().singleOrNull()
        if ((leafParentNew == null && this.leafParent != null) || (leafParentNew != null && this.leafParent == null) || (leafParentNew != null && this.leafParent != null && (this.leafParent as WorkspaceEntityBase).id != (leafParentNew as WorkspaceEntityBase).id)) {
          this.leafParent = leafParentNew
        }
      }
    }


    override var entitySource: EntitySource
      get() = getEntityData().entitySource
      set(value) {
        checkModificationAllowed()
        getEntityData().entitySource = value
        changedProperty.add("entitySource")

      }

    override var data: String
      get() = getEntityData().data
      set(value) {
        checkModificationAllowed()
        getEntityData().data = value
        changedProperty.add("data")
      }

    override var mainParent: TreeMultiparentRootEntity?
      get() {
        val _diff = diff
        return if (_diff != null) {
          _diff.extractOneToManyParent(MAINPARENT_CONNECTION_ID, this) ?: this.entityLinks[EntityLink(false,
                                                                                                      MAINPARENT_CONNECTION_ID)] as? TreeMultiparentRootEntity
        }
        else {
          this.entityLinks[EntityLink(false, MAINPARENT_CONNECTION_ID)] as? TreeMultiparentRootEntity
        }
      }
      set(value) {
        checkModificationAllowed()
        val _diff = diff
        if (_diff != null && value is ModifiableWorkspaceEntityBase<*> && value.diff == null) {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*>) {
            val data = (value.entityLinks[EntityLink(true, MAINPARENT_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, MAINPARENT_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable
          _diff.addEntity(value)
        }
        if (_diff != null && (value !is ModifiableWorkspaceEntityBase<*> || value.diff != null)) {
          _diff.updateOneToManyParentOfChild(MAINPARENT_CONNECTION_ID, this, value)
        }
        else {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*>) {
            val data = (value.entityLinks[EntityLink(true, MAINPARENT_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, MAINPARENT_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable

          this.entityLinks[EntityLink(false, MAINPARENT_CONNECTION_ID)] = value
        }
        changedProperty.add("mainParent")
      }

    override var leafParent: TreeMultiparentLeafEntity?
      get() {
        val _diff = diff
        return if (_diff != null) {
          _diff.extractOneToManyParent(LEAFPARENT_CONNECTION_ID, this) ?: this.entityLinks[EntityLink(false,
                                                                                                      LEAFPARENT_CONNECTION_ID)] as? TreeMultiparentLeafEntity
        }
        else {
          this.entityLinks[EntityLink(false, LEAFPARENT_CONNECTION_ID)] as? TreeMultiparentLeafEntity
        }
      }
      set(value) {
        checkModificationAllowed()
        val _diff = diff
        if (_diff != null && value is ModifiableWorkspaceEntityBase<*> && value.diff == null) {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*>) {
            val data = (value.entityLinks[EntityLink(true, LEAFPARENT_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, LEAFPARENT_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable
          _diff.addEntity(value)
        }
        if (_diff != null && (value !is ModifiableWorkspaceEntityBase<*> || value.diff != null)) {
          _diff.updateOneToManyParentOfChild(LEAFPARENT_CONNECTION_ID, this, value)
        }
        else {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*>) {
            val data = (value.entityLinks[EntityLink(true, LEAFPARENT_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, LEAFPARENT_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable

          this.entityLinks[EntityLink(false, LEAFPARENT_CONNECTION_ID)] = value
        }
        changedProperty.add("leafParent")
      }

    // List of non-abstract referenced types
    var _children: List<TreeMultiparentLeafEntity>? = emptyList()
    override var children: List<TreeMultiparentLeafEntity>
      get() {
        // Getter of the list of non-abstract referenced types
        val _diff = diff
        return if (_diff != null) {
          _diff.extractOneToManyChildren<TreeMultiparentLeafEntity>(CHILDREN_CONNECTION_ID, this)!!.toList() + (this.entityLinks[EntityLink(
            true, CHILDREN_CONNECTION_ID)] as? List<TreeMultiparentLeafEntity> ?: emptyList())
        }
        else {
          this.entityLinks[EntityLink(true, CHILDREN_CONNECTION_ID)] as? List<TreeMultiparentLeafEntity> ?: emptyList()
        }
      }
      set(value) {
        // Setter of the list of non-abstract referenced types
        checkModificationAllowed()
        val _diff = diff
        if (_diff != null) {
          for (item_value in value) {
            if (item_value is ModifiableWorkspaceEntityBase<*> && (item_value as? ModifiableWorkspaceEntityBase<*>)?.diff == null) {
              // Backref setup before adding to store
              if (item_value is ModifiableWorkspaceEntityBase<*>) {
                item_value.entityLinks[EntityLink(false, CHILDREN_CONNECTION_ID)] = this
              }
              // else you're attaching a new entity to an existing entity that is not modifiable

              _diff.addEntity(item_value)
            }
          }
          _diff.updateOneToManyChildrenOfParent(CHILDREN_CONNECTION_ID, this, value)
        }
        else {
          for (item_value in value) {
            if (item_value is ModifiableWorkspaceEntityBase<*>) {
              item_value.entityLinks[EntityLink(false, CHILDREN_CONNECTION_ID)] = this
            }
            // else you're attaching a new entity to an existing entity that is not modifiable
          }

          this.entityLinks[EntityLink(true, CHILDREN_CONNECTION_ID)] = value
        }
        changedProperty.add("children")
      }

    override fun getEntityData(): TreeMultiparentLeafEntityData = result ?: super.getEntityData() as TreeMultiparentLeafEntityData
    override fun getEntityClass(): Class<TreeMultiparentLeafEntity> = TreeMultiparentLeafEntity::class.java
  }
}

class TreeMultiparentLeafEntityData : WorkspaceEntityData<TreeMultiparentLeafEntity>() {
  lateinit var data: String

  fun isDataInitialized(): Boolean = ::data.isInitialized

  override fun wrapAsModifiable(diff: MutableEntityStorage): ModifiableWorkspaceEntity<TreeMultiparentLeafEntity> {
    val modifiable = TreeMultiparentLeafEntityImpl.Builder(null)
    modifiable.allowModifications {
      modifiable.diff = diff
      modifiable.snapshot = diff
      modifiable.id = createEntityId()
      modifiable.entitySource = this.entitySource
    }
    modifiable.changedProperty.clear()
    return modifiable
  }

  override fun createEntity(snapshot: EntityStorage): TreeMultiparentLeafEntity {
    return getCached(snapshot) {
      val entity = TreeMultiparentLeafEntityImpl(this)
      entity.entitySource = entitySource
      entity.snapshot = snapshot
      entity.id = createEntityId()
      entity
    }
  }

  override fun getEntityInterface(): Class<out WorkspaceEntity> {
    return TreeMultiparentLeafEntity::class.java
  }

  override fun serialize(ser: EntityInformation.Serializer) {
  }

  override fun deserialize(de: EntityInformation.Deserializer) {
  }

  override fun createDetachedEntity(parents: List<WorkspaceEntity>): WorkspaceEntity {
    return TreeMultiparentLeafEntity(data, entitySource) {
      this.mainParent = parents.filterIsInstance<TreeMultiparentRootEntity>().singleOrNull()
      this.leafParent = parents.filterIsInstance<TreeMultiparentLeafEntity>().singleOrNull()
    }
  }

  override fun getRequiredParents(): List<Class<out WorkspaceEntity>> {
    val res = mutableListOf<Class<out WorkspaceEntity>>()
    return res
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as TreeMultiparentLeafEntityData

    if (this.entitySource != other.entitySource) return false
    if (this.data != other.data) return false
    return true
  }

  override fun equalsIgnoringEntitySource(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as TreeMultiparentLeafEntityData

    if (this.data != other.data) return false
    return true
  }

  override fun hashCode(): Int {
    var result = entitySource.hashCode()
    result = 31 * result + data.hashCode()
    return result
  }

  override fun hashCodeIgnoringEntitySource(): Int {
    var result = javaClass.hashCode()
    result = 31 * result + data.hashCode()
    return result
  }

  override fun collectClassUsagesData(collector: UsedClassesCollector) {
    collector.sameForAllEntities = true
  }
}
