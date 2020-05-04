package com.johnturkson.demo.database

import com.johnturkson.demo.model.LinkinbioPost

interface Database {
    suspend fun getLinkinbioPost(id: String): LinkinbioPost?
    
    suspend fun getLinkinbioPosts(user: String): List<LinkinbioPost>
    
    suspend fun createLinkinbioPost(linkinbioPost: LinkinbioPost): LinkinbioPost?
    
    suspend fun updateLinkinbioPost(linkinbioPost: LinkinbioPost): LinkinbioPost?
    
    suspend fun deleteLinkinbioPost(id: String): LinkinbioPost?
}
