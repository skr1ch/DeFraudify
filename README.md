# DeFraudify Backend

This is the backend component of the DeFraudify application. It's a Java-based REST API built with Spring Boot that analyzes text messages for potential fraud or scam content. It leverages a fine-tuned BERT model for machine learning-based detection, integrates with the Groq API for generating explanations, and uses the Google Programmable Search Engine (PSE) to find related resources.

## Table of Contents

- [Features](#features)
- [Technologies Used](#technologies-used)
- [Prerequisites](#prerequisites)
- [Project Setup](#project-setup)
  - [1. Clone the Repository](#1-clone-the-repository)
  - [2. Configure API Keys](#2-configure-api-keys)
  - [3. (If needed) Convert Model Format](#3-if-needed-convert-model-format)
- [Running the Application Locally](#running-the-application-locally)
  - [Using Maven](#using-maven)
- [API Endpoints](#api-endpoints)
  - [`POST /api/analyze`](#post-apianalyze)
  - [`GET /api/health`](#get-apihealth)
- [Project Structure](#project-structure)
- [Deployment](#deployment)
  - [Azure App Service (Using Maven Plugin)](#azure-app-service-using-maven-plugin)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Fraud Detection:** Analyzes text input using a machine learning model to calculate a scam probability.
- **Heuristic Boosting:** Applies rule-based logic to adjust the ML model's score based on common scam keywords and patterns.
- **AI-Powered Explanation:** Integrates with the Groq API to provide clear, contextual explanations for the scam score.
- **Related Resource Search:** Uses Google Programmable Search Engine to find relevant articles, advice, or reports related to the detected scam type.
- **RESTful API:** Exposes endpoints for frontend integration and external consumption.

## Technologies Used

- **Java 17**
- **Spring Boot 3.x**
- **Maven**
- **Deep Java Library (DJL)**
- **Hugging Face Transformers (via PyTorch model)**
- **Groq API**
- **Google Programmable Search Engine API**

## Prerequisites

- **JDK 17:** Install the Java Development Kit version 17 or later.
- **Maven:** Install Apache Maven for building the project.
- **Python 3.7+ (Optional, for model conversion):** Required if you need to convert your model from `.safetensors` to `.bin` format.
- **API Keys:**
  - **Groq API Key:** For generating explanations.
  - **Google PSE API Key & Search Engine ID:** For finding related links.

## Project Setup

### 1. Clone the Repository

```bash
git clone <your-repo-url> # Replace with your actual repository URL
cd DeFraudify/backend
