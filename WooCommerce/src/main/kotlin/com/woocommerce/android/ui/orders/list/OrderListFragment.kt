package com.woocommerce.android.ui.orders.list

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MenuItem.OnActionExpandListener
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SearchView.OnQueryTextListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.paging.PagedList
import com.google.android.material.tabs.TabLayout
import com.woocommerce.android.AppPrefs
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.extensions.onScrollDown
import com.woocommerce.android.extensions.onScrollUp
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.base.TopLevelFragment
import com.woocommerce.android.ui.base.UIMessageResolver
import com.woocommerce.android.ui.main.MainActivity
import com.woocommerce.android.ui.main.MainNavigationRouter
import com.woocommerce.android.ui.orders.OrderStatusListView
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.util.WooAnimUtils
import com.woocommerce.android.util.ActivityUtils
import org.wordpress.android.util.ActivityUtils as WPActivityUtils
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_order_list.*
import kotlinx.android.synthetic.main.fragment_order_list.orderRefreshLayout
import kotlinx.android.synthetic.main.fragment_order_list.view.*
import kotlinx.android.synthetic.main.order_list_view.view.*
import org.wordpress.android.fluxc.model.WCOrderStatusModel
import org.wordpress.android.fluxc.network.rest.wpcom.wc.order.CoreOrderStatus.PROCESSING
import org.wordpress.android.util.DisplayUtils
import java.util.Locale
import javax.inject.Inject

class OrderListFragment : TopLevelFragment(),
        OrderStatusListView.OrderStatusListListener, OnQueryTextListener, OnActionExpandListener, OrderListListener {
    companion object {
        const val TAG: String = "OrderListFragment"
        const val STATE_KEY_LIST = "list-state"
        const val STATE_KEY_REFRESH_PENDING = "is-refresh-pending"
        const val STATE_KEY_ACTIVE_FILTER = "active-order-status-filter"
        const val STATE_KEY_SEARCH_QUERY = "search-query"
        const val STATE_KEY_IS_SEARCHING = "is_searching"
        const val STATE_KEY_IS_FILTER_ENABLED = "is_filter_enabled"

        private const val SEARCH_TYPING_DELAY_MS = 500L
        private const val ORDER_TAB_DEFAULT = 1
        private const val ORDER_TAB_PROCESSING = 0

        fun newInstance(orderStatusFilter: String? = null) =
            OrderListFragment().apply { this.orderStatusFilter = orderStatusFilter }
    }

    @Inject internal lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject internal lateinit var uiMessageResolver: UIMessageResolver
    @Inject internal lateinit var selectedSite: SelectedSite
    @Inject internal lateinit var currencyFormatter: CurrencyFormatter

    private lateinit var viewModel: OrderListViewModel

    private var listState: Parcelable? = null // Save the state of the recycler view
    private var orderStatusFilter: String? = null

    // Alias for interacting with [viewModel.isSearching] so the value is always identical
    // to the real value on the UI side.
    private var isSearching: Boolean
        private set(value) { viewModel.isSearching = value }
        get() = viewModel.isSearching
    var isRefreshing: Boolean = false
    var isRefreshPending = true // If true, the fragment will refresh its orders when its visible

    private var orderListMenu: Menu? = null
    private var searchMenuItem: MenuItem? = null
    private var searchView: SearchView? = null
    private val searchHandler = Handler()

    // Alias for interacting with [viewModel.searchQuery] so the value is always identical
    // to the real value on the UI side.
    private var searchQuery: String
        private set(value) { viewModel.searchQuery = value }
        get() = viewModel.searchQuery

    /**
     * flag to check if the user selected any order status from the order status list
     * If true, the data in the order list tab currently visible, will be refreshed
     */
    var isFilterEnabled: Boolean = false

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
        savedInstanceState?.let { bundle ->
            listState = bundle.getParcelable(STATE_KEY_LIST)
            orderStatusFilter = bundle.getString(STATE_KEY_ACTIVE_FILTER, null)
            isSearching = bundle.getBoolean(STATE_KEY_IS_SEARCHING)
            isFilterEnabled = bundle.getBoolean(STATE_KEY_IS_FILTER_ENABLED)
            searchQuery = bundle.getString(STATE_KEY_SEARCH_QUERY, "")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater?.inflate(R.menu.menu_order_list_fragment, menu)

        orderListMenu = menu
        searchMenuItem = menu?.findItem(R.id.menu_search)
        searchView = searchMenuItem?.actionView as SearchView?
        searchView?.queryHint = getString(R.string.orderlist_search_hint)

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        refreshOptionsMenu()
        super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_order_list, container, false)
        with(view) {
            orderRefreshLayout?.apply {
                activity?.let { activity ->
                    setColorSchemeColors(
                            ContextCompat.getColor(activity, R.color.colorPrimary),
                            ContextCompat.getColor(activity, R.color.colorAccent),
                            ContextCompat.getColor(activity, R.color.colorPrimaryDark)
                    )
                }
                // Set the scrolling view in the custom SwipeRefreshLayout
                scrollUpChild = order_list_view.ordersList
                setOnRefreshListener {
                    AnalyticsTracker.track(Stat.ORDERS_LIST_PULLED_TO_REFRESH)

                    orderRefreshLayout.isRefreshing = false
                    refreshOrders()
                }
            }
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViewModel()
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        resources.getStringArray(R.array.order_list_tabs).toList()
                .forEachIndexed { index, title ->
                    val tab = tab_layout.newTab().apply {
                        text = title
                        tag = title
                    }
                    tab_layout.addTab(tab)

                    // Start with the tab user had previously selected
                    // if no tab is selected, default to the `Processing` Tab
                    if (index == getTabPosition()) {
                        orderStatusFilter = getOrderStatusByTab(tab)
                        tab.select()
                    }
                }

        order_list_view.init(currencyFormatter = currencyFormatter, orderListListener = this)
        order_list_view.initEmptyView(selectedSite.get())
        order_status_list_view.init(listener = this)

        listState?.let {
            order_list_view.onFragmentRestoreInstanceState(it)
            listState = null
        }

        tab_layout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val previousOrderStatus = orderStatusFilter
                orderStatusFilter = getOrderStatusByTab(tab)

                if (orderStatusFilter != previousOrderStatus) {
                    // store the selected tab in SharedPrefs
                    // clear the adapter data
                    // load orders based on the order status
                    AppPrefs.setSelectedOrderListTab(tab.position)
                    order_list_view.clearAdapterData()
                    isRefreshing = true
                    viewModel.loadList(orderStatusFilter)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {
                order_list_view.scrollToTop()
            }
        })

        val filterOrSearchEnabled = isFilterEnabled || isSearching
        showTabs(!filterOrSearchEnabled)
        enableToolbarElevation(filterOrSearchEnabled)

        if (isOrderStatusFilterEnabled() && isActive && !deferInit) {
            viewModel.loadList(orderStatusFilter)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        checkOrientation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_KEY_LIST, order_list_view.onFragmentSavedInstanceState())
        outState.putBoolean(STATE_KEY_REFRESH_PENDING, isRefreshPending)
        outState.putString(STATE_KEY_ACTIVE_FILTER, orderStatusFilter)
        outState.putBoolean(STATE_KEY_IS_SEARCHING, isSearching)
        outState.putBoolean(STATE_KEY_IS_FILTER_ENABLED, isFilterEnabled)
        outState.putString(STATE_KEY_SEARCH_QUERY, searchQuery)

        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        disableSearchListeners()
        searchView = null
        orderListMenu = null
        searchMenuItem = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        if (hidden) {
            // restore the toolbar elevation when the order list screen is hidden
            enableToolbarElevation(true)
        } else {
            // silently refresh if this fragment is no longer hidden
            val isChildFragmentShowing = isChildFragmentShowing()
            enableToolbarElevation(isChildFragmentShowing)
            if (!isChildFragmentShowing) {
                showOptionsMenu(true)

                if (isSearching) {
                    clearSearchResults()
                } else {
                    viewModel.reloadListFromCache()
                }
            }
        }
    }

    override fun onReturnedFromChildFragment() {
        showOptionsMenu(true)
        enableToolbarElevation(isChildFragmentShowing())

        if (isOrderStatusFilterEnabled()) {
            viewModel.reloadListFromCache()
        } else {
            searchHandler.postDelayed({ searchView?.setQuery(searchQuery, true) }, 20)
        }
    }

    /**
     * This is a replacement for activity?.invalidateOptionsMenu() since that causes the
     * search menu item to collapse
     */
    private fun refreshOptionsMenu() {
        if (!isChildFragmentShowing() && isSearching) {
            enableSearchListeners()
            searchMenuItem?.expandActionView()
            if (isFilterEnabled) enableFilterListeners()
        } else {
            val showSearch = shouldShowSearchMenuItem()
            searchMenuItem?.let {
                if (it.isActionViewExpanded) it.collapseActionView()
                if (it.isVisible != showSearch) it.isVisible = showSearch
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.menu_search -> {
                AnalyticsTracker.track(Stat.ORDERS_LIST_MENU_SEARCH_TAPPED)
                enableSearchListeners()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun isChildFragmentShowing() = (activity as? MainNavigationRouter)?.isChildFragmentShowing() ?: false

    private fun shouldShowSearchMenuItem(): Boolean {
        val isChildShowing = isChildFragmentShowing()
        return when {
            (isChildShowing) -> false
            (isFilterEnabled) -> false
            else -> true
        }
    }

    private fun isShowingAllOrders(): Boolean {
        return !isSearching && orderStatusFilter.isNullOrEmpty()
    }

    private fun getOrderStatusOptions() = viewModel.orderStatusOptions.value.orEmpty()

    private fun isOrderListEmpty() =
            getOrderStatusOptions()?.filterValues { it.statusCount > 0 }.isNullOrEmpty()

    private fun isShowingProcessingOrders() = tab_layout.selectedTabPosition == ORDER_TAB_PROCESSING

    override fun getFragmentTitle() = if (isFilterEnabled || isSearching) "" else getString(R.string.orders)

    override fun refreshFragmentState() {
        if (isActive) {
            order_list_view?.clearAdapterData()
            refreshOrders() // reload the active list from scratch
        } else {
            // refresh order status options in the background even when order list is hidden
            // This is so that when an order status change takes place, we need to refresh the order
            // status count in the local cache
            refreshOrderStatusOptions()
        }
    }

    override fun scrollToTop() {
        order_list_view.scrollToTop()
    }

    private fun initializeViewModel() {
        viewModel = ViewModelProviders.of(this, viewModelFactory).get(OrderListViewModel::class.java)

        // setup observers
        viewModel.isFetchingFirstPage.observe(this, Observer {
            orderRefreshLayout?.isRefreshing = it == true
        })

        viewModel.isLoadingMore.observe(this, Observer {
            it?.let { isLoadingMore ->
                order_list_view.setLoadingMoreIndicator(active = isLoadingMore)
            }
        })

        viewModel.orderStatusOptions.observe(this, Observer {
            it?.let { options ->
                // So the order status can be matched to the appropriate label
                order_list_view.setOrderStatusOptions(options)

                updateOrderStatusList(options)
            }
        })

        viewModel.pagedListData.observe(this, Observer {
            updatePagedListData(it)
        })

        viewModel.showSnackbarMessage.observe(this, Observer { msg ->
            msg?.let { uiMessageResolver.showSnack(it) }
        })

        viewModel.scrollToPosition.observe(this, Observer {
            // TODO AMANDA - needed?
        })

        viewModel.emptyViewState.observe(this, Observer {
            it?.let { emptyViewState -> order_list_view?.updateEmptyViewForState(emptyViewState) }
        })

        viewModel.shareStore.observe(this, Observer {
            AnalyticsTracker.track(Stat.ORDERS_LIST_SHARE_YOUR_STORE_BUTTON_TAPPED)
            selectedSite.getIfExists()?.let { site ->
                context?.let { ctx ->
                    selectedSite.getIfExists()?.let {
                        ActivityUtils.shareStoreUrl(ctx, site.url)
                    }
                }
            }
        })

        viewModel.start()
        viewModel.loadList(orderStatusFilter, searchQuery)
    }

    private fun updatePagedListData(pagedListData: PagedList<OrderListItemUIType>?) {
        order_list_view?.submitPagedList(pagedListData)

        if (pagedListData?.size != 0 && isSearching) {
            WPActivityUtils.hideKeyboard(activity)
        }
    }

    /**
     * We use this to clear the options menu when navigating to a child destination - otherwise this
     * fragment's menu will continue to appear when the child is shown
     */
    private fun showOptionsMenu(show: Boolean) {
        setHasOptionsMenu(show)
        if (show) {
            refreshOptionsMenu()
        }
    }

    override fun openOrderDetail(remoteOrderId: Long) {
        showOptionsMenu(false)
        (activity as? MainNavigationRouter)?.showOrderDetail(selectedSite.get().id, remoteOrderId)
    }

    private fun updateOrderStatusList(orderStatusList: Map<String, WCOrderStatusModel>) {
        order_list_view_root.visibility = View.VISIBLE
        order_status_list_view.updateOrderStatusListView(orderStatusList.values.toList())
        // if empty view is currently displayed, then refresh the empty view message
        // based on the order status list count
        if (order_list_view.isEmptyViewVisible()) {

            // FIXME AMANDA - empty view
//            showEmptyView(true)
        }
    }

    override fun onOrderStatusSelected(orderStatus: String?) {
        if (orderStatusFilter == orderStatus) {
            // Filter has not changed. Exit.
            return
        }

        orderStatusFilter = orderStatus
        if (isAdded) {
            AnalyticsTracker.track(
                    Stat.ORDERS_LIST_FILTER,
                    mapOf(AnalyticsTracker.KEY_STATUS to orderStatus.orEmpty())
            )

            isRefreshing = true
            enableFilterListeners()
            order_list_view.clearAdapterData()

            viewModel.loadList(statusFilter = orderStatus)

            updateActivityTitle()
            searchMenuItem?.isVisible = shouldShowSearchMenuItem()
        }
    }

    override fun onFragmentScrollDown() {
        onScrollDown()
    }

    override fun onFragmentScrollUp() {
        onScrollUp()
    }

    /**
     * Method to return the default tab position to display.
     * If there are no orders for a site or if there are no orders to process, the `All Orders` tab should be displayed.
     * If there are orders/processing orders, default to whatever the user previously selected (Processing, by default)
     */
    private fun getTabPosition(): Int {
        val orderStatusOptions = getOrderStatusOptions()
        return if (orderStatusOptions.isEmpty() || orderStatusOptions[PROCESSING.value]?.statusCount == 0) {
            ORDER_TAB_DEFAULT
        } else AppPrefs.getSelectedOrderListTabPosition()
    }

    private fun getOrderStatusByTab(tab: TabLayout.Tab): String? {
        return when {
            isFilterEnabled -> orderStatusFilter
            tab.position == 0 -> (tab.tag as? String)?.toLowerCase(Locale.getDefault())
            else -> null
        }
    }

    // region search
    override fun onQueryTextSubmit(query: String): Boolean {
        handleNewSearchRequest(query)
        WPActivityUtils.hideKeyboard(activity)
        return true
    }

    override fun onQueryTextChange(newText: String): Boolean {
        // only display the order status list if the search query is empty
        when {
            newText.isEmpty() -> displayOrderStatusListView()
            else -> hideOrderStatusListView()
        }

        if (newText.length > 2) {
            submitSearchDelayed(newText)
        } else {
            clearOrderListData()
        }

        // FIXME AMANDA - empty view
//        showEmptyView(false)

        return true
    }

    override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
        clearOrderListData()
        showTabs(false)
        isSearching = true
        checkOrientation()
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
        if (isFilterEnabled) {
            disableFilterListeners()
            enableSearchListeners()
            searchMenuItem?.isVisible = false
            searchView?.post { searchMenuItem?.expandActionView() }
        } else {
            clearSearchResults()
            searchMenuItem?.isVisible = true
        }
        return true
    }

    private fun clearSearchResults() {
        if (isSearching) {
            searchQuery = ""
            isSearching = false
            isRefreshing = true
            disableSearchListeners()
            updateActivityTitle()
            refreshOrderStatusOptions()
            searchMenuItem?.collapseActionView()

            viewModel.loadList(orderStatusFilter)
        }
    }

    /**
     * Submit the search after a brief delay unless the query has changed - this is used to
     * perform a search while the user is typing
     */
    private fun submitSearchDelayed(query: String) {
        searchHandler.postDelayed({
            searchView?.let {
                // submit the search if the searchView's query still matches the passed query
                if (query == it.query.toString()) handleNewSearchRequest(query)
            }
        }, SEARCH_TYPING_DELAY_MS)
    }

    /**
     * Only fired while the user is actively typing in the search
     * field.
     */
    private fun handleNewSearchRequest(query: String) {
        AnalyticsTracker.track(
                Stat.ORDERS_LIST_FILTER,
                mapOf(AnalyticsTracker.KEY_SEARCH to query))

        searchQuery = query
        submitSearchQuery(searchQuery)
    }

    /**
     * Loads a new list with the search query. This can be called while the
     * user is interacting with the search component, or to reload the
     * view state.
     */
    private fun submitSearchQuery(query: String) {
        viewModel.loadList(searchQuery = query)
    }

    private fun isOrderStatusFilterEnabled() = isFilterEnabled || !isSearching

    private fun enableToolbarElevation(enable: Boolean) {
        activity?.toolbar?.elevation = if (enable) resources.getDimension(R.dimen.appbar_elevation) else 0f
    }

    private fun showTabs(show: Boolean) {
        if (show && tab_layout.visibility != View.VISIBLE) {
            WooAnimUtils.fadeIn(tab_layout)
        } else if (!show && tab_layout.visibility != View.GONE) {
            tab_layout.visibility = View.GONE
        }
    }

    private fun refreshOrders() {
        viewModel.fetchFirstPage()
        refreshOrderStatusOptions()
    }

    private fun refreshOrderStatusOptions() {
        // FIXME AMANDA
    }

    private fun disableSearchListeners() {
        orderListMenu?.findItem(R.id.menu_settings)?.isVisible = true
        orderListMenu?.findItem(R.id.menu_support)?.isVisible = true
        order_list_view_root.visibility = View.VISIBLE
        searchMenuItem?.setOnActionExpandListener(null)
        searchView?.setOnQueryTextListener(null)
        hideOrderStatusListView()
        showTabs(true)
        (activity as? MainActivity)?.showBottomNav()
        enableToolbarElevation(false)
    }

    /**
     * Method called when user clicks on the search menu icon.
     * 1. The settings menu is hidden when the search filter is active to prevent the search view
     *    getting collapsed if the settings menu from the [MainActivity] is clicked.
     * 2. The order status list view is displayed by default
     */
    private fun enableSearchListeners() {
        orderListMenu?.findItem(R.id.menu_settings)?.isVisible = false
        orderListMenu?.findItem(R.id.menu_support)?.isVisible = false
        searchMenuItem?.setOnActionExpandListener(this)
        searchView?.setOnQueryTextListener(this)
        displayOrderStatusListView()

        (activity as? MainActivity)?.hideBottomNav()
        enableToolbarElevation(true)
    }

    /**
     * Method called when user clicks on an order status from [OrderStatusListView]
     * 1. Hide the order status view
     * 2. Disable search
     * 3. Display the order status selected in the search query text area
     * 4. Set [isFilterEnabled] flag to true.
     *    This is because once an order status is selected and the order list for that status is displayed,
     *    when back is clicked, the order list needs to be refreshed again from the api,
     *    since we only store the orders for a particular status in local cache.
     */
    private fun enableFilterListeners() {
        isFilterEnabled = true
        hideOrderStatusListView()
        searchView?.queryHint = getString(R.string.orders)
                .plus(orderStatusFilter?.let { filter ->
                    val orderStatusLabel = getOrderStatusOptions()[filter]?.label
                    getString(R.string.orderlist_filtered, orderStatusLabel)
                } ?: "")

        searchView?.findViewById<EditText>(R.id.search_src_text)?.also {
            it.setHintTextColor(Color.WHITE)
            it.isEnabled = false
        }
        (activity as? MainActivity)?.showBottomNav()
    }

    /**
     * Method called when user clicks on the back button after selecting an order status.
     * 1. Hide the order status view
     * 2. Enable search again and update the hint query
     */
    private fun disableFilterListeners() {
        if (isFilterEnabled) {
            isFilterEnabled = false
            searchView?.findViewById<EditText>(R.id.search_src_text)?.also {
                it.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.default_search_hint_text))
                it.isEnabled = true
            }
            searchView?.queryHint = getString(R.string.orderlist_search_hint)

            val tabPosition = getTabPosition()
            orderStatusFilter = tab_layout.getTabAt(tabPosition)?.let {
                it.select()
                getOrderStatusByTab(it)
            }

            viewModel.loadList(orderStatusFilter)

            (activity as? MainActivity)?.hideBottomNav()
        }
    }

    private fun displayOrderStatusListView() {
        order_status_list_view.visibility = View.VISIBLE
        orderRefreshLayout.isEnabled = false
    }

    private fun hideOrderStatusListView() {
        order_status_list_view.visibility = View.GONE
        orderRefreshLayout.isEnabled = true
    }

    private fun checkOrientation() {
        val isLandscape = DisplayUtils.isLandscape(context)
        if (isLandscape && isSearching) {
            searchView?.post { searchView?.clearFocus() }
        }
    }

    /**
     * Method to clear adapter data only if order filter is not enabled.
     * This is to prevent the order filter list data from being cleared when fragment state is restored
     */
    private fun clearOrderListData() {
        if (!isFilterEnabled) {
            order_list_view.clearAdapterData()
        }
    }
    // endregion
}
