package com.sleepguard.poc

import android.content.Context

/**
 * Façade over [MorningReportDao] for the per-night user self-report. On-device only; mirrors the
 * style of [NightRepository]. Synchronous for now (the DB is built with allowMainThreadQueries).
 */
class MorningReportRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).morningReportDao()

    fun get(nightOf: String): MorningReportEntity? = dao.getByNight(nightOf)

    fun save(report: MorningReportEntity) = dao.upsert(report)

    /** Set of nights that already have a self-report (for History "filled" badges). */
    fun filledNights(): Set<String> = dao.getFilledNights().toSet()

    fun clearAll() = dao.clearAll()
}
