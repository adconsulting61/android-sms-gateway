package me.capcom.smsgateway.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentSimStatsBinding
import me.capcom.smsgateway.databinding.ItemSimStatBinding
import me.capcom.smsgateway.modules.messages.vm.SimStatRow
import me.capcom.smsgateway.modules.messages.vm.SimStatsViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Shows how many messages have been sent through each SIM card.
 */
class SimStatsFragment : Fragment() {
    private val viewModel: SimStatsViewModel by viewModel()

    private var _binding: FragmentSimStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.simStats.observe(viewLifecycleOwner) { rows ->
            renderRows(rows)
        }
    }

    private fun renderRows(rows: List<SimStatRow>) {
        binding.containerSimStats.removeAllViews()

        if (rows.isEmpty()) {
            val item = ItemSimStatBinding.inflate(layoutInflater, binding.containerSimStats, false)
            item.textViewSimTitle.text = getString(R.string.no_sim_cards_detected)
            item.textViewSimSubtitle.visibility = View.GONE
            item.textViewSimSent.visibility = View.GONE
            binding.containerSimStats.addView(item.root)
            return
        }

        rows.forEach { row ->
            val item = ItemSimStatBinding.inflate(layoutInflater, binding.containerSimStats, false)

            item.textViewSimTitle.text = listOfNotNull("SIM ${row.simNumber}", row.carrierName)
                .joinToString(" — ")

            if (row.phoneNumber != null) {
                item.textViewSimSubtitle.text = row.phoneNumber
                item.textViewSimSubtitle.visibility = View.VISIBLE
            } else {
                item.textViewSimSubtitle.visibility = View.GONE
            }

            item.textViewSimSent.text = getString(R.string.sim_stats_sent_count, row.sent)

            binding.containerSimStats.addView(item.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = SimStatsFragment()
    }
}
