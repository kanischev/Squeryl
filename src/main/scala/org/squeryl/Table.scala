/*******************************************************************************
 * Copyright 2010 Maxime Lévesque
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.squeryl;

import dsl.ast._
import dsl.{QueryDsl}
import internals.{FieldMetaData, NoOpOutMapper, FieldReferenceLinker, StatementWriter}
import java.sql.{Statement}
import scala.reflect.Manifest


private [squeryl] object DummySchema extends Schema

class Table[T] private [squeryl] (n: String, c: Class[T], schema: Schema) extends View[T](n, c, schema) {

  def this(n:String)(implicit manifestT: Manifest[T]) =
    this(n, manifestT.erasure.asInstanceOf[Class[T]], DummySchema)  

  private def _dbAdapter = Session.currentSession.databaseAdapter
  
  def insert(t: T): T = {

    val o = t.asInstanceOf[AnyRef]
    val sess = Session.currentSession
    val sw = new StatementWriter(_dbAdapter)
    _dbAdapter.writeInsert(t, this, sw)

    val st = 
      if(_dbAdapter.supportsAutoIncrementInColumnDeclaration)
        sess.connection.prepareStatement(sw.statement, Statement.RETURN_GENERATED_KEYS)
      else if( posoMetaData.primaryKey != None) {
        val autoIncPk = new Array[String](1)
        autoIncPk(0) = posoMetaData.primaryKey.get.columnName
        sess.connection.prepareStatement(sw.statement, autoIncPk)
      }
      else
        sess.connection.prepareStatement(sw.statement)

    val (cnt, s) = _dbAdapter.executeUpdateForInsert(sess, sw, st)

    if(cnt != 1)
      error("failed to insert")

    if(posoMetaData.primaryKey != None && posoMetaData.primaryKey.get.isAutoIncremented) {      
      val rs = s.getGeneratedKeys
      try {
        sess._addResultSet(rs)
        assert(rs.next,
          "getGeneratedKeys returned no rows for the auto incremented\n"+
          " primary key of table '" + name + "' JDBC3 feature might not be supported, \n or"+
          " column might not be defined as auto increment")
        posoMetaData.primaryKey.get.setFromResultSet(o, rs, 1)
      }
      finally {
        rs.close
      }
    }

    _setPersisted(t)
    
    t
  }

  def insert(t: Query[T]) = error("not implemented")

  def insert(e: Iterable[T]):Unit =
    _batchedUpdateOrInsert(e, posoMetaData.fieldsMetaData.filter(fmd => !fmd.isAutoIncremented), true, false)

  /**
   * isInsert if statement is insert otherwise update
   */
  private def _batchedUpdateOrInsert(e: Iterable[T], fmds: Iterable[FieldMetaData], isInsert: Boolean, checkOCC: Boolean):Unit = {
    
    val it = e.iterator

    if(it.hasNext) {

      val e0 = it.next
      val sess = Session.currentSession
      val dba = _dbAdapter
      val sw = new StatementWriter(dba)

      if(isInsert)
        dba.writeInsert(e0, this, sw)
      else
        dba.writeUpdate(e0, this, sw, checkOCC)
      
      val st = sess.connection.prepareStatement(sw.statement)
      dba.prepareStatement(sess.connection, sw, st, sess)
      st.addBatch

      var updateCount = 1

      while(it.hasNext) {
        val eN = it.next.asInstanceOf[AnyRef]      
        var idx = 1
        val f = fmds.foreach(fmd => {
          st.setObject(idx, dba.convertToJdbcValue(fmd.get(eN)))
          idx += 1
        })
        st.addBatch
        updateCount += 1
      }

      val execResults = st.executeBatch
      
      if(checkOCC)
        for(b <- execResults)
          if(b == 0) {
            val updateOrInsert = if(isInsert) "insert" else "update"
            throw new StaleUpdateException(
              "Attemped to "+updateOrInsert+" stale object under optimistic concurrency control")
          }
    }
  }

  /**
   * Updates without any Optimistic Concurrency Control check 
   */
  def forceUpdate(o: T)(implicit ev: T <:< KeyedEntity[_]) =
    _update(o, false)
  
  def update(o: T)(implicit ev: T <:< KeyedEntity[_]):Unit =
    _update(o, true)

  def update(o: Iterable[T])(implicit ev: T <:< KeyedEntity[_]):Unit =
    _update(o, true)

  def forceUpdate(o: Iterable[T])(implicit ev: T <:< KeyedEntity[_]):Unit =
    _update(o, false)

  private def _update(o: T, checkOCC: Boolean) = {

    val dba = Session.currentSession.databaseAdapter
    val sw = new StatementWriter(dba)
    dba.writeUpdate(o, this, sw, checkOCC)

    val (cnt, s) = dba.executeUpdate(Session.currentSession, sw)

    if(cnt != 1) {
      if(checkOCC && posoMetaData.isOptimistic) {
        val version = posoMetaData.optimisticCounter.get.get(o.asInstanceOf[AnyRef])
        throw new StaleUpdateException(
           "Object "+name + "(id=" + o.asInstanceOf[KeyedEntity[_]].id + ", occVersionNumber=" + version +
           ") has become stale, it cannot be updated under optimistic concurrency control")
      }
      else
        error("failed to update")
    }
  }

  private def _update(e: Iterable[T], checkOCC: Boolean):Unit = {

    val pkMd = posoMetaData.primaryKey.get

    val fmds = List(
      posoMetaData.fieldsMetaData.filter(fmd=> fmd != pkMd && ! fmd.isOptimisticCounter).toList,            
      List(pkMd),
      posoMetaData.optimisticCounter.toList
    ).flatten

    _batchedUpdateOrInsert(e, fmds, false, checkOCC)
  }
  
  def update(s: T =>UpdateStatement):Int = {

    val vxn = new ViewExpressionNode(this)
    vxn.sample =
       posoMetaData.createSample(FieldReferenceLinker.createCallBack(vxn))    
    val us = s(vxn.sample)
    vxn.parent = Some(us)

    var idGen = 0
    vxn.visitDescendants((node,parent,i) => {

      if(node.parent == None)
        node.parent = parent

      if(node.isInstanceOf[UniqueIdInAliaseRequired]) {
        val nxn = node.asInstanceOf[UniqueIdInAliaseRequired]
        nxn.uniqueId = Some(idGen)
        idGen += 1
      }
    })

    val dba = _dbAdapter
    val sw = new StatementWriter(dba)
    dba.writeUpdate(this, us, sw)
    val res = dba.executeUpdate(Session.currentSession, sw)
    res._1    
  }
  
  def delete(q: Query[T]): Int = {

    val queryAst = q.ast.asInstanceOf[QueryExpressionElements]
    queryAst.inhibitAliasOnSelectElementReference = true

    val sw = new StatementWriter(_dbAdapter)
    _dbAdapter.writeDelete(this, queryAst.whereClause, sw)

    val (cnt, s) = _dbAdapter.executeUpdate(Session.currentSession, sw)

    cnt
  }

  def deleteWhere(whereClause: T => LogicalBoolean)(implicit dsl: QueryDsl): Int =
    delete(dsl.from(this)(t => dsl.where(whereClause(t)).select(t)))      

  def delete[K](k: K)(implicit ev: T <:< KeyedEntity[K], dsl: QueryDsl): Boolean  = {
    import dsl._
    val q = from(this)(a => dsl.where {
      a.id
      FieldReferenceLinker.createEqualityExpressionWithLastAccessedFieldReferenceAndConstant(k)
    } select(a))

    val deleteCount = this.delete(q)
    assert(deleteCount <= 1, "Query :\n" + q.dumpAst + "\nshould have deleted at most 1 row but has deleted " + deleteCount)
    deleteCount == 1
  }

  def insertOrUpdate(o: T)(implicit ev: T <:< KeyedEntity[_]): T = {
    if(o.isPersisted)
      update(o)
    else
      insert(o)
    o
  }
}
