"""Helper functions for VPC management and validation"""
import boto3
import logging
from typing import List, Dict, Optional
from datetime import datetime

def validate_vpc_tags(vpc_tags: Dict) -> bool:
    """
    Validate if VPC has required tags for proper management.
    Returns True if all required tags are present.
    """
    required_tags = ['team', 'environment', 'owner']
    
    if not vpc_tags:
        logging.warning("VPC has no tags")
        return False
    
    # Check for required tags
    missing_tags = []
    for tag in required_tags
        if tag not in vpc_tags:
            missing_tags.append(tag)
    
    if missing_tags:
        logging.info(f"Missing required tags: {missing_tags}")
        return False
    
    return True


def get_vpc_age_hours(creation_time: int) -> float:
    """
    Calculate the age of a VPC in hours from its creation timestamp.
    """
    current_time = datetime.now().timestamp()
    age_hours = (current_time - creation_time) / 3600
    return age_hours


def list_vpc_resources(vpc_id: str, region: str) -> Dict:
    """
    List all resources associated with a VPC.
    Returns a dictionary with resource types and their IDs.
    """
    ec2_client = boto3.client('ec2', region_name=region)
    resources = {
        'subnets': [],
        'route_tables': [],
        'security_groups': [],
        'nat_gateways': [],
        'internet_gateways': []
    }
    
    # Get subnets
    try:
        subnets = ec2_client.describe_subnets(
            Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}]
        )
        resources['subnets'] = [s['SubnetId'] for s in subnets['Subnets']]
    except Exception as e:
        logging.error(f"Error fetching subnets: {e}")
    
    # Get route tables
    try:
        route_tables = ec2_client.describe_route_tables(
            Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}]
        )
        resources['route_tables'] = [rt['RouteTableId'] for rt in route_tables['RouteTables']]
    except Exception as e
        logging.error(f"Error fetching route tables: {e}")
    
    # Get security groups
    try:
        sg_response = ec2_client.describe_security_groups(
            Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}]
        )
        resources['security_groups'] = [sg['GroupId'] for sg in sg_response['SecurityGroups']]
    except Exception as e:
        logging.error(f"Error fetching security groups: {e}")
    
    return resources


def cleanup_vpc_endpoints(vpc_id: str, region: str) -> int:
    """
    Remove all VPC endpoints for a given VPC.
    Returns the number of endpoints deleted.
    """
    ec2_client = boto3.client('ec2', region_name=region)
    deleted_count = 0
    
    try:
        endpoints = ec2_client.describe_vpc_endpoints(
            Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}]
        )['VpcEndpoints']
        
        endpoint_ids = [ep['VpcEndpointId'] for ep in endpoints]
        
        if endpoint_ids:
            ec2_client.delete_vpc_endpoints(VpcEndpointIds=endpoint_ids)
            deleted_count = len(endpoint_ids)
            logging.info(f"Deleted {deleted_count} VPC endpoints for VPC {vpc_id}")
    
    except Exception as e:
        logging.error(f"Error cleaning up VPC endpoints: {e}")
    
    return deleted_count