package com.johnturkson.demo.database

import com.johnturkson.demo.model.LinkinbioPost
import kotlinx.coroutines.flow.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*

class LinkinbioDatabase(url: String, properties: Properties = Properties()) : Database {
    private val connection: Connection = DriverManager.getConnection(url, properties)
    
    override suspend fun getLinkinbioPost(id: String): LinkinbioPost? {
        val query = """SELECT * FROM "LinkinbioPosts" WHERE id = ? LIMIT 1"""
        val statement = connection.prepareStatement(query).apply {
            setString(1, id)
        }
        val result = statement.executeQuery().asFlow().toList().firstOrNull()
        return result?.asLinkinbioPost()
    }
    
    override suspend fun getLinkinbioPosts(user: String): List<LinkinbioPost> {
        val query = """SELECT * FROM "LinkinbioPosts" WHERE "user" = ?"""
        val statement = connection.prepareStatement(query).apply {
            setString(1, user)
        }
        val results = statement.executeQuery().asFlow()
        return results.map { result -> result.asLinkinbioPost() }.toList()
    }
    
    override suspend fun createLinkinbioPost(linkinbioPost: LinkinbioPost): LinkinbioPost? {
        val query =
            """INSERT INTO "LinkinbioPosts" (id, "user", url, image) VALUES (?, ?, ?, ?) RETURNING *"""
        val (id, user, url, image) = linkinbioPost
        val statement = connection.prepareStatement(query).apply {
            setString(1, id)
            setString(2, user)
            setString(3, url)
            setString(4, image)
        }
        val result = statement.executeQuery().asFlow().toList().firstOrNull()
        return result?.asLinkinbioPost()
    }
    
    override suspend fun updateLinkinbioPost(linkinbioPost: LinkinbioPost): LinkinbioPost? {
        val query =
            """UPDATE "LinkinbioPosts" SET id = ?, "user" = ?, url = ?, image = ? WHERE id = ? RETURNING *"""
        val (id, user, url, image) = linkinbioPost
        val statement = connection.prepareStatement(query).apply {
            setString(1, id)
            setString(2, user)
            setString(3, url)
            setString(4, image)
            setString(5, id)
        }
        val result = statement.executeQuery().asFlow().toList().firstOrNull()
        return result?.asLinkinbioPost()
    }
    
    override suspend fun deleteLinkinbioPost(id: String): LinkinbioPost? {
        val query = """DELETE FROM "LinkinbioPosts" WHERE id = ? RETURNING *"""
        val statement = connection.prepareStatement(query).apply {
            setString(1, id)
        }
        val result = statement.executeQuery().asFlow().toList().firstOrNull()
        return result?.asLinkinbioPost()
    }
    
    fun generateNewId(): String {
        // val length = 16
        // val digits = 1..9
        // val builder = StringBuilder()
        // repeat(length) { builder.append(Random.nextInt(digits)) }
        // return builder.toString()
        return System.currentTimeMillis().toString()
    }
}

fun ResultSet.asFlow(): Flow<ResultSet> {
    return if (!this.isBeforeFirst) emptyFlow() else flow {
        while (this@asFlow.next()) {
            emit(this@asFlow)
            if (this@asFlow.isLast) break
        }
    }
}

fun ResultSet.asLinkinbioPost(): LinkinbioPost {
    val id = this.getString("id")
    val user = this.getString("user")
    val url = this.getString("url")
    val image = this.getString("image")
    return LinkinbioPost(id, user, url, image)
}
