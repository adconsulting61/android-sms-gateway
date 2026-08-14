package me.capcom.smsgateway.modules.messages.vm

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import me.capcom.smsgateway.data.entities.SimSentCount
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.messages.MessagesRepository

data class SimStatRow(
    val simNumber: Int,
    val phoneNumber: String?,
    val carrierName: String?,
    val sent: Int,
)

class SimStatsViewModel(
    private val repository: MessagesRepository,
    private val context: Context,
) : ViewModel() {
    val simStats: LiveData<List<SimStatRow>> = MediatorLiveData<List<SimStatRow>>().apply {
        addSource(repository.simSentCounts) { counts ->
            value = buildRows(counts)
        }
    }

    private fun buildRows(counts: List<SimSentCount>): List<SimStatRow> {
        val simCards = SubscriptionsHelper.getActiveSimCards(context)
        val sentBySim = counts.filter { it.simNumber != null }
            .associate { it.simNumber!! to it.sent }

        val rows = simCards.map { sim ->
            SimStatRow(
                simNumber = sim.simNumber,
                phoneNumber = sim.phoneNumber,
                carrierName = sim.carrierName,
                sent = sentBySim[sim.simNumber] ?: 0,
            )
        }.toMutableList()

        // SIMs with past sends that aren't currently active/detected (e.g. removed)
        // still get a row, so sending history isn't silently hidden.
        val knownSimNumbers = simCards.map { it.simNumber }.toSet()
        sentBySim.keys.filter { it !in knownSimNumbers }.sorted().forEach { simNumber ->
            rows.add(
                SimStatRow(
                    simNumber = simNumber,
                    phoneNumber = null,
                    carrierName = null,
                    sent = sentBySim.getValue(simNumber),
                )
            )
        }

        return rows.sortedBy { it.simNumber }
    }
}
