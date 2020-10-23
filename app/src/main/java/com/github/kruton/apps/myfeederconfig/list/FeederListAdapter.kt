package com.github.kruton.apps.myfeederconfig.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kruton.apps.myfeederconfig.Feeder
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.ItemFeederBinding
import com.polidea.rxandroidble2.scan.ScanResult

class FeederListAdapter(
    private val viewModel: FeederListViewModel,
    repository: Repository
) : RecyclerView.Adapter<FeederListAdapter.FeederViewHolder>() {
    private val data = repository.feeders

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeederViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val itemBinding = ItemFeederBinding.inflate(layoutInflater, parent, false)
        return FeederViewHolder(itemBinding, viewModel)
    }

    override fun onBindViewHolder(holder: FeederViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    class FeederViewHolder(
        private val binding: ItemFeederBinding,
        private val viewModel: FeederListViewModel
    ): RecyclerView.ViewHolder(binding.root) {
        fun bind(feeder: Feeder) {
            binding.feeder = feeder
            binding.viewModel = viewModel
            binding.executePendingBindings()
        }
    }

    private fun ScanResult.toFeeder(): Feeder {
        val device = this.bleDevice
        val name = device.name ?: "Unknown"
        val mac = device.macAddress ?: "??:??:??:??:??:??"
        return Feeder(device = device, name = name, mac = mac)
    }

    fun addScanResult(bleScanResult: ScanResult) {
        // Not the best way to ensure distinct devices, just for the sake of the demo.
        data.withIndex()
            .firstOrNull { it.value.device == bleScanResult.bleDevice }
            ?.let {
                // device already in data list => update
                data[it.index] = bleScanResult.toFeeder()
                notifyItemChanged(it.index)
            }
            ?: run {
                // new device => add to data list
                with(data) {
                    add(bleScanResult.toFeeder())
                    sortBy { it.mac }
                }
                notifyDataSetChanged()
            }
    }

    fun clearScanResults() {
        data.clear()
        notifyDataSetChanged()
    }
}