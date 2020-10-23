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
import com.github.kruton.apps.myfeederconfig.R
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.FragmentFeederListBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FeederListFragment : Fragment() {
    private val viewModel: FeederListViewModel by viewModels()

    @Inject
    lateinit var repository: Repository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
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
        viewModel.adapter = adapter

        viewModel.permissionsResults.observe(viewLifecycleOwner, Observer { event ->
            event.getContentIfNotHandled()?.let { success ->
                if (!success) {
                    activity?.findViewById<FloatingActionButton>(R.id.fab)?.let { fab ->
                        Snackbar.make(
                            fab,
                            "Permissions were not granted.",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })

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
