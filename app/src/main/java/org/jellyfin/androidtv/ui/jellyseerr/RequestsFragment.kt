package org.jellyfin.androidtv.ui.jellyseerr

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.FragmentJellyseerrRequestsBinding
import org.jellyfin.androidtv.ui.base.BaseFragment
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RequestsFragment : BaseFragment(R.layout.fragment_jellyseerr_requests) {
	private val viewModel: JellyseerrViewModel by viewModel()

	private var _binding: FragmentJellyseerrRequestsBinding? = null
	private val binding get() = _binding!!

	private lateinit var requestsAdapter: RequestsAdapter

	override fun setupUI(view: View, savedInstanceState: Bundle?) {
		_binding = FragmentJellyseerrRequestsBinding.bind(view)
		
		requestsAdapter = RequestsAdapter()
		binding.requestsList.apply {
			adapter = requestsAdapter
			layoutManager = LinearLayoutManager(requireContext())
		}
		
		binding.refreshButton.setOnClickListener {
			viewModel.loadRequests()
		}
		
		lifecycleScope.launch {
			kotlinx.coroutines.delay(1000)
			viewModel.loadRequests()
		}
	}

	override fun setupObservers() {
		collectFlow(viewModel.loadingState) { state ->
			when (state) {
				is JellyseerrLoadingState.Loading -> {
					binding.loadingState.visibility = View.VISIBLE
					binding.emptyState.visibility = View.GONE
				}
				is JellyseerrLoadingState.Success -> {
					binding.loadingState.visibility = View.GONE
				}
				is JellyseerrLoadingState.Error -> {
					binding.loadingState.visibility = View.GONE
					binding.emptyState.visibility = View.VISIBLE
					showError("Error: ${state.message}")
				}
				else -> {}
			}
		}

		collectFlow(viewModel.userRequests) { requests ->
			requestsAdapter.submitList(requests)
			if (requests.isEmpty()) {
				binding.emptyState.visibility = View.VISIBLE
				binding.requestsList.visibility = View.GONE
			} else {
				binding.emptyState.visibility = View.GONE
				binding.requestsList.visibility = View.VISIBLE
			}
		}

		collectFlow(viewModel.isAvailable) { isAvailable ->
			binding.notConnectedWarning.visibility =
				if (isAvailable) View.GONE else View.VISIBLE
		}
	}

	override fun onDestroyView() {
		_binding = null
		super.onDestroyView()
	}
}
