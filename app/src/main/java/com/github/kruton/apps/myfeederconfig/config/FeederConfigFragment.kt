package com.github.kruton.apps.myfeederconfig.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.github.kruton.apps.myfeederconfig.R
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.FragmentFeederConfigBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Presents the configuration of the Feeder.
 */
@AndroidEntryPoint
class FeederConfigFragment : Fragment() {
    private val viewModel: FeederConfigViewModel by viewModels()

    @Inject
    lateinit var repository: Repository

    private val args: FeederConfigFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentFeederConfigBinding.inflate(inflater, container, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        viewModel.setFeeder(args.mac)

        viewModel.sendClicked.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let {
                viewModel.sendWifiConfig { throwable ->
                    activity?.findViewById<FloatingActionButton>(R.id.fab)?.let { fab ->
                        Snackbar.make(
                            fab,
                            "Error checking WiFi status",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                    Timber.d(throwable)
                }
            }
        }

        return binding.root
    }
}