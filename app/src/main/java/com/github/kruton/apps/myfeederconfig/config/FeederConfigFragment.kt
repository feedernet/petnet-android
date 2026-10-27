package com.github.kruton.apps.myfeederconfig.config

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.github.kruton.apps.myfeederconfig.R
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.FragmentFeederConfigBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Presents the configuration of the Feeder. */
@AndroidEntryPoint
class FeederConfigFragment : Fragment() {
    private val viewModel: FeederConfigViewModel by viewModels()

    @Inject lateinit var repository: Repository

    private val args: FeederConfigFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val binding = FragmentFeederConfigBinding.inflate(inflater, container, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner
        viewModel.setFeeder(args.mac)

        binding.openWebInterface.setOnClickListener {
            val address = viewModel.webAddress.value.orEmpty()
            if (address.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                } catch (e: ActivityNotFoundException) {
                    Snackbar.make(binding.root, R.string.web_browser_unavailable, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        viewModel.sendClicked.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let {
                viewModel.sendWifiConfig { _ ->
                    Snackbar.make(binding.root, "Error checking WiFi status", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        viewModel.confirmMqtt.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mqtt_confirm_title)
                    .setMessage(R.string.mqtt_confirm_body)
                    .setPositiveButton(R.string.mqtt_confirm_action) { _, _ ->
                        viewModel.saveBroker(true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return binding.root
    }
}
