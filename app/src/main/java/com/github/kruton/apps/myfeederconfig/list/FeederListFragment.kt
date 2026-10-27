package com.github.kruton.apps.myfeederconfig.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kruton.apps.myfeederconfig.R
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.FragmentFeederListBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FeederListFragment : Fragment() {
    private val viewModel: FeederListViewModel by viewModels()

    @Inject
    lateinit var repository: Repository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding: FragmentFeederListBinding =
            DataBindingUtil.inflate(inflater, R.layout.fragment_feeder_list, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        val recyclerView = binding.recyclerView
        val adapter = FeederListAdapter(viewModel, repository)
        recyclerView.adapter = adapter
        recyclerView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        var permissionDenied = false
        fun updateStatus() {
            val status = when {
                permissionDenied -> R.string.scan_permission_denied
                viewModel.scanError.value == true -> R.string.scan_failed
                adapter.itemCount == 0 -> R.string.scan_searching
                else -> 0
            }
            if (status != 0) binding.scanStatus.setText(status)
            binding.scanStatus.visibility = if (status == 0) View.GONE else View.VISIBLE
        }
        val dataObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateStatus()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateStatus()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateStatus()
        }
        adapter.registerAdapterDataObserver(dataObserver)
        viewModel.scanError.observe(viewLifecycleOwner) { updateStatus() }
        if (viewModel.permissionsGranted) viewModel.adapter = adapter
        updateStatus()

        viewModel.permissionsResults.observe(
            viewLifecycleOwner,
            Observer { event ->
                event.getContentIfNotHandled()?.let { success ->
                    permissionDenied = !success
                    if (success) viewModel.adapter = adapter
                    updateStatus()
                }
            }
        )

        viewModel.feederClicked.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { feeder ->
                val action = FeederListFragmentDirections.configFeeder(feeder.mac)
                findNavController().navigate(action)
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.adapter = null
    }
}
