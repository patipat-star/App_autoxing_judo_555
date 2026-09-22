package com.example.pcoverlaycontrol

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pcoverlaycontrol.databinding.FragmentFirstBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private var webSocket: WebSocket? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonFirst.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }

        binding.buttonThird.setOnClickListener {
            disconnectAndStopRobot()
        }
    }

    private fun disconnectAndStopRobot() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("ws://192.168.100.55:8090/ws/v2/topics")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val stopPayload = JSONObject().apply {
                    put("topic", "/cmd_vel")
                    put("data", JSONObject().apply {
                        put("linear_x", 0.0)
                        put("angular_z", 0.0)
                    })
                }
                webSocket.send(stopPayload.toString())
                webSocket.close(1000, "User disconnect")
            }
        })

        val serviceIntent = Intent(requireContext(), FloatingService::class.java)
        requireContext().stopService(serviceIntent)

        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webSocket?.close(1000, "View Destroyed")
        _binding = null
    }
}